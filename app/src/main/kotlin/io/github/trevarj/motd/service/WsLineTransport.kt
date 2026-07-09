package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.transport.IrcTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * IRC-over-WebSocket transport (plans/19 §3.3). Speaks the IRCv3 `text.ircv3.net` subprotocol:
 * each inbound WS text message is exactly one IRC line (no trailing CRLF), and each outbound line
 * is sent as one WS text message with the CRLF stripped. On the wire this is an ordinary TLS
 * handshake to the bouncer host followed by an HTTP `Upgrade: websocket`, so it blends with HTTPS
 * and clears networks that only permit web ports.
 *
 * TLS, hostname verification, and TOFU leaf pinning are preserved by handing OkHttp the same
 * [SSLSocketFactory]/[X509TrustManager]/[HostnameVerifier] that [AppTransportFactory] builds for
 * the raw TCP/TLS path. Pinning therefore still keys on the real bouncer host from [url].
 */
class WsLineTransport(
    private val url: String,
    /** Non-null for `wss://`/`https://`; supplies the pinning + client-cert SSL stack. */
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val trustManager: X509TrustManager? = null,
    private val hostnameVerifier: HostnameVerifier? = null,
    /** Optional `Origin` header for soju Origin/Host authorization; null keeps OkHttp's default. */
    private val origin: String? = null,
) : IrcTransport {

    private companion object {
        // IRCv3 WebSocket subprotocol: one IRC line per text frame, no trailing CRLF.
        const val SUBPROTOCOL = "text.ircv3.net"
        const val CONNECT_TIMEOUT_MS = 15_000L
        // 512-byte IRC line limit (RFC 1459) incl. the CRLF the WS framing omits: 510 payload bytes.
        const val MAX_LINE_BYTES = 512
    }

    // Inbound lines from onMessage; consumed once by `incoming`. Unlimited so the socket read
    // thread never blocks on a slow consumer (matches OkioLineTransport's channelFlow behavior).
    private val inbound = Channel<String>(Channel.UNLIMITED)

    // Completes when the handshake opens (Unit) or fails (exception), so connect() can suspend.
    private val opened = CompletableDeferred<Unit>()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closed = false

    override suspend fun connect() {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // IRC connections are long-lived and mostly idle; disable the read timeout and rely on
            // the IrcClient ping watchdog for liveness, exactly like OkioLineTransport (soTimeout=0).
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
        if (sslSocketFactory != null && trustManager != null) {
            builder.sslSocketFactory(sslSocketFactory, trustManager)
        }
        if (hostnameVerifier != null) {
            builder.hostnameVerifier(hostnameVerifier)
        }
        val client = builder.build()

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .apply { if (origin != null) header("Origin", origin) }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Each text frame is exactly one IRC line (no CRLF). Defensively strip any trailing
                // CRLF a lenient server might still send.
                inbound.trySend(text.removeSuffix("\r\n").removeSuffix("\n"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Peer initiated close: complete the inbound flow so IrcClient sees a clean EOF.
                closed = true
                inbound.close()
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed = true
                inbound.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Surface the failure to connect() if still handshaking, else terminate the flow so
                // the watchdog/reconnect path fires — same disconnect semantics as a socket error.
                if (!opened.isCompleted) opened.completeExceptionally(t)
                closed = true
                inbound.close(t)
            }
        }

        // newWebSocket returns immediately; the handshake runs on OkHttp's dispatcher. Await open.
        webSocket = client.newWebSocket(request, listener)
        opened.await()
    }

    override val incoming: Flow<String> = inbound.consumeAsFlow()

    override suspend fun send(line: String) {
        val ws = webSocket ?: throw IllegalStateException("connect() not called")
        // One IRC line per WS text message, no CRLF. Trim any caller-supplied CRLF and enforce the
        // 512-byte line limit so WS framing preserves TCP line semantics.
        val trimmed = line.removeSuffix("\r\n").removeSuffix("\n").take(MAX_LINE_BYTES)
        if (!ws.send(trimmed)) {
            throw IOException("websocket send failed (closed or backpressured)")
        }
    }

    override suspend fun close() {
        closed = true
        // 1000 = normal closure. queueSize/enqueue is thread-safe; ignore if already gone.
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        inbound.close()
    }
}

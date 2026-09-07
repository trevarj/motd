package io.github.trevarj.motd.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.system.OsConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.obfs.VlessLink
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InterfaceAddress
import javax.inject.Inject
import javax.inject.Singleton
import java.net.NetworkInterface as JavaNetworkInterface

/** Loopback endpoint returned by the embedded obfuscation core. */
data class LocalSocksEndpoint(
    val host: String = "127.0.0.1",
    val port: Int,
)

/** A running embedded SOCKS core lease owned by one physical IRC connection attempt. */
data class LocalSocksLease(
    val endpoint: LocalSocksEndpoint,
    val release: () -> Unit,
)

/**
 * Small, generated-code-free boundary around libbox. The eventual libbox adapter is responsible
 * for starting its SOCKS inbound and returning the port it actually bound; the connection layer
 * deliberately knows only this contract.
 */
interface LocalSocksEngine {
    fun start(configJson: String): Result<Int>

    fun stop()
}

/**
 * Owns embedded-core instances for EMBEDDED_REALITY connections. Each distinct (link, ownerKey)
 * pair maps to its own libbox command server with a reference-counted SOCKS inbound; the
 * connection layer keys the owner on the network row, so every physical IRC actor runs its own
 * core. That per-actor split is deliberate (see `resolveTransportProxy`): sharing one inbound
 * across a bouncer root and its children made the native core serialize their TLS streams at the
 * capability transition, so both sessions looked Ready while post-registration writes vanished.
 * Callers that pass no ownerKey (legacy/start) still share one core per identical link.
 */
@Singleton
class LocalSocksProvider private constructor(
    private val engineFactory: () -> LocalSocksEngine,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        diagnostics: DiagnosticLogger,
    ) : this({ LibboxLocalSocksEngine(context) }, diagnostics)

    private data class ActiveCore(
        val engine: LocalSocksEngine,
        val endpoint: LocalSocksEndpoint,
        var refs: Int,
    )

    private val activeCores = LinkedHashMap<String, ActiveCore>()

    companion object {
        /** Test seam: production construction is Hilt-only. Diagnostics first so the factory
         *  lambda stays trailing for the existing `forTest { ... }` call sites. */
        internal fun forTest(
            diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
            engineFactory: () -> LocalSocksEngine,
        ): LocalSocksProvider = LocalSocksProvider(engineFactory, diagnostics)
    }

    @Synchronized
    fun start(link: VlessLink): Result<LocalSocksEndpoint> = acquire(link).map { it.endpoint }

    @Synchronized
    fun acquire(
        link: VlessLink,
        ownerKey: String? = null,
    ): Result<LocalSocksLease> {
        val configJson = link.toSingBoxConfigJson()
        val coreKey = if (ownerKey.isNullOrBlank()) configJson else "$configJson\nowner=$ownerKey"
        activeCores[coreKey]?.let { core ->
            core.refs++
            var released = false
            return Result.success(
                LocalSocksLease(core.endpoint) {
                    synchronized(this) {
                        if (!released) {
                            released = true
                            release(coreKey)
                        }
                    }
                },
            )
        }
        val engine = engineFactory()
        // Wall time of the whole native bring-up (setup + command server + service load). This is
        // the number the startup plan's pre-warm step is gated on; classification only — the link
        // and endpoint never reach the journal.
        val startedAtMs = System.nanoTime() / 1_000_000
        return engine
            .start(configJson)
            .onFailure { error ->
                diagnostics.record("obfs", "core_start_failed") {
                    mapOf(
                        "owner" to ownerKey,
                        "error" to error::class.simpleName,
                        "duration_ms" to (System.nanoTime() / 1_000_000 - startedAtMs),
                    )
                }
            }.mapCatching { port ->
                require(port in 1..65535) { "Embedded SOCKS provider returned invalid port" }
                diagnostics.record("obfs", "core_start") {
                    mapOf(
                        "owner" to ownerKey,
                        "duration_ms" to (System.nanoTime() / 1_000_000 - startedAtMs),
                        "active_cores" to activeCores.size + 1,
                    )
                }
                val endpoint = LocalSocksEndpoint(port = port)
                activeCores[coreKey] = ActiveCore(engine, endpoint, refs = 1)
                var released = false
                LocalSocksLease(endpoint) {
                    synchronized(this) {
                        if (!released) {
                            released = true
                            release(coreKey)
                        }
                    }
                }
            }.onFailure { engine.stop() }
    }

    @Synchronized
    private fun release(configJson: String) {
        val core = activeCores[configJson] ?: return
        core.refs--
        if (core.refs <= 0) {
            activeCores.remove(configJson)?.engine?.stop()
        }
    }

    @Synchronized
    fun stop() {
        activeCores.values.forEach { it.engine.stop() }
        activeCores.clear()
    }
}

/** Direct adapter for the pinned, arm64-only libbox AAR. No generated API leaks out of service. */
private class LibboxLocalSocksEngine(
    context: Context,
) : LocalSocksEngine {
    private val appContext = context.applicationContext
    private val platform = AndroidPlatform(appContext)
    private var server: CommandServer? = null
    private var initialized = false

    @Synchronized
    override fun start(configJson: String): Result<Int> =
        runCatching {
            initialize()
            val port = selectLocalSocksPort(Libbox::availablePort)
            val commandServer = CommandServer(NoOpCommandServerHandler, platform)
            commandServer.start()
            try {
                // Libbox treats zero as a literal port and returns zero on Android. Start its
                // availability scan at a nonzero range, then give sing-box the selected port.
                commandServer.startOrReloadService(
                    configJson.replace("\"listen_port\":0", "\"listen_port\":$port"),
                    OverrideOptions(),
                )
                server = commandServer
                port
            } catch (error: Throwable) {
                commandServer.close()
                throw error
            }
        }

    @Synchronized
    override fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.closeService() }
        runCatching { current.close() }
    }

    private fun initialize() {
        if (initialized) return
        Libbox.setup(
            SetupOptions().apply {
                basePath = appContext.filesDir.absolutePath
                workingPath = appContext.filesDir.absolutePath
                tempPath = appContext.cacheDir.absolutePath
                fixAndroidStack = true
            },
        )
        initialized = true
    }
}

/**
 * Libbox's port helper scans from its argument; zero is not an ephemeral-port sentinel in the
 * Android binding. The sing-box config still binds the selected port to 127.0.0.1 exclusively.
 */
internal fun selectLocalSocksPort(findAvailablePort: (Int) -> Int): Int =
    findAvailablePort(20_000).also { port ->
        require(port in 1..65535) { "libbox did not allocate a local SOCKS port" }
    }

internal fun localDnsResponse(
    network: String,
    addresses: Array<InetAddress>,
): String {
    val matching =
        when (network) {
            "ip4" -> addresses.filterIsInstance<Inet4Address>()
            "ip6" -> addresses.filterIsInstance<Inet6Address>()
            else -> throw UnsupportedOperationException("Unsupported DNS address family: $network")
        }
    return matching.joinToString("\n") { it.hostAddress!!.substringBefore('%') }
}

/** SOCKS-only configuration uses no VPN, system proxy, interface monitor, or notifications. */
private object NoOpCommandServerHandler : CommandServerHandler {
    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply {
            available = false
            enabled = false
        }

    override fun serviceReload() = Unit

    override fun serviceStop() = Unit

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun writeDebugMessage(message: String) = Unit
}

/**
 * gomobile bridges interface return values through generated proxy objects. Returning null for
 * those proxies is not equivalent to an empty value: the Go side dereferences the proxy before
 * it can decide that this SOCKS-only host has no interfaces or certificates. Keep the Android
 * host deliberately featureless, but always return concrete, empty bridge implementations.
 */
private class NetworkInterfaces(
    private val interfaces: Iterator<io.nekohasekai.libbox.NetworkInterface>,
) : io.nekohasekai.libbox.NetworkInterfaceIterator {
    override fun hasNext() = interfaces.hasNext()

    override fun next(): io.nekohasekai.libbox.NetworkInterface = interfaces.next()
}

private object EmptyNetworkInterfaces : io.nekohasekai.libbox.NetworkInterfaceIterator {
    override fun hasNext() = false

    override fun next(): io.nekohasekai.libbox.NetworkInterface = throw NoSuchElementException("No platform network interfaces are exposed")
}

private class Strings(
    private val strings: List<String>,
) : io.nekohasekai.libbox.StringIterator {
    private var index = 0

    override fun hasNext() = index < strings.size

    override fun len() = strings.size

    override fun next(): String = strings[index++]
}

private object EmptyStrings : io.nekohasekai.libbox.StringIterator {
    override fun hasNext() = false

    override fun len() = 0

    override fun next(): String = throw NoSuchElementException("No platform strings are exposed")
}

/**
 * Minimal Android platform adapter modelled after sing-box-for-Android's platform bridge.
 * libbox creates a default-interface monitor even for a SOCKS inbound, so a static empty
 * adapter is not sufficient: it needs a real active interface and lifecycle-safe callbacks.
 * This deliberately exposes no TUN/VPN or system-proxy behaviour.
 */
private class AndroidPlatform(
    context: Context,
) : PlatformInterface {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var interfaceMonitor: io.nekohasekai.libbox.InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ponytail: Ingress needs only A/AAAA; raw DNS exchange is deliberately unsupported.
    private val dnsTransport =
        object : LocalDNSTransport {
            override fun raw() = false

            override fun lookup(
                ctx: ExchangeContext,
                network: String,
                domain: String,
            ) {
                val addresses = connectivity.activeNetwork?.getAllByName(domain) ?: InetAddress.getAllByName(domain)
                ctx.success(localDnsResponse(network, addresses))
            }

            override fun exchange(
                ctx: ExchangeContext,
                message: ByteArray,
            ): Unit = throw UnsupportedOperationException("Raw DNS exchange is not supported")
        }

    override fun autoDetectInterfaceControl(fd: Int) = Unit

    override fun clearDNSCache() = Unit

    @Synchronized
    override fun closeDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener?) {
        networkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        interfaceMonitor = null
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): io.nekohasekai.libbox.ConnectionOwner = io.nekohasekai.libbox.ConnectionOwner()

    @Suppress("DEPRECATION")
    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator {
        val interfaces =
            runCatching {
                val javaInterfaces =
                    JavaNetworkInterface
                        .getNetworkInterfaces()
                        .toList()
                        .associateBy { it.name }
                connectivity.allNetworks
                    .mapNotNull { network ->
                        val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                        val name = properties.interfaceName ?: return@mapNotNull null
                        val javaInterface = javaInterfaces[name] ?: return@mapNotNull null
                        val capabilities = connectivity.getNetworkCapabilities(network)
                        toLibboxInterface(javaInterface, properties, capabilities)
                    }.distinctBy { it.name }
            }.getOrElse { emptyList() }
        return if (interfaces.isEmpty()) EmptyNetworkInterfaces else NetworkInterfaces(interfaces.iterator())
    }

    override fun includeAllNetworks() = false

    override fun localDNSTransport(): LocalDNSTransport = dnsTransport

    override fun openTun(options: io.nekohasekai.libbox.TunOptions?): Int = throw UnsupportedOperationException("libbox TUN is not enabled by motd")

    // sing-box's Android platform implementation returns an object even when Wi-Fi is absent.
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState = io.nekohasekai.libbox.WIFIState("", "")

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) = Unit

    @Synchronized
    override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener?) {
        closeDefaultInterfaceMonitor(interfaceMonitor)
        interfaceMonitor = listener
        if (listener == null) return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = reportDefaultInterface()

                override fun onLost(network: Network) = reportDefaultInterface()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    properties: LinkProperties,
                ) = reportDefaultInterface()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = reportDefaultInterface()
            }
        networkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
        reportDefaultInterface()
    }

    override fun systemCertificates(): io.nekohasekai.libbox.StringIterator = EmptyStrings

    override fun underNetworkExtension() = false

    // Match sing-box-for-Android's platform bridge. Let Android own socket/interface selection;
    // binding the VLESS socket through the Java interface inventory can leave later writes on a
    // stale route after the bouncer child capability transition.
    override fun usePlatformAutoDetectInterfaceControl() = true

    override fun useProcFS() = false

    private fun reportDefaultInterface() {
        val listener = interfaceMonitor ?: return
        val network = connectivity.activeNetwork
        val properties = network?.let(connectivity::getLinkProperties)
        val name = properties?.interfaceName.orEmpty()
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val expensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val constrained =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
            } else {
                false
            }
        listener.updateDefaultInterface(name, index, expensive, constrained)
    }

    private fun toLibboxInterface(
        network: JavaNetworkInterface,
        properties: LinkProperties,
        capabilities: NetworkCapabilities?,
    ): io.nekohasekai.libbox.NetworkInterface =
        io.nekohasekai.libbox.NetworkInterface().apply {
            index = network.index
            mtu = runCatching { network.mtu }.getOrDefault(0)
            name = network.name
            addresses = Strings(network.interfaceAddresses.map { address -> address.toLibboxPrefix() })
            flags = interfaceFlags(network, capabilities)
            type = capabilities.interfaceType()
            setDNSServer(Strings(properties.dnsServers.mapNotNull { it.hostAddress }))
            metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        }

    private fun NetworkCapabilities?.interfaceType(): Int =
        when {
            this?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Libbox.InterfaceTypeWIFI
            this?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Libbox.InterfaceTypeCellular
            this?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Libbox.InterfaceTypeEthernet
            else -> Libbox.InterfaceTypeOther
        }

    private fun interfaceFlags(
        network: JavaNetworkInterface,
        capabilities: NetworkCapabilities?,
    ): Int {
        var flags = 0
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (network.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
        if (network.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
        if (network.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
        return flags
    }

    private fun InterfaceAddress.toLibboxPrefix(): String {
        // A scoped IPv6 host string (for example, fe80::1%wlan0) is not accepted by
        // netip.ParsePrefix. This is the same scope-stripping conversion used upstream.
        val host =
            if (address is Inet6Address) {
                Inet6Address.getByAddress(address.address).hostAddress
            } else {
                address.hostAddress
            }
        return "$host/$networkPrefixLength"
    }
}

/**
 * Configuration handed to the eventual libbox adapter. The core chooses/binds the ephemeral
 * loopback port and reports it from [LocalSocksEngine.start].
 */
internal fun VlessLink.toSingBoxConfigJson(): String =
    Json.encodeToString(
        kotlinx.serialization.json.JsonObject
            .serializer(),
        buildJsonObject {
            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "socks")
                            put("listen", "127.0.0.1")
                            put("listen_port", 0)
                        },
                    )
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(Json.parseToJsonElement(toSingBoxOutboundJson()))
                },
            )
            // sing-box otherwise selects its implicit direct outbound for unmatched SOCKS traffic.
            // Every connection received by this private inbound must traverse the VLESS tunnel.
            put(
                "route",
                buildJsonObject {
                    put("final", "motd-vless")
                },
            )
        },
    )

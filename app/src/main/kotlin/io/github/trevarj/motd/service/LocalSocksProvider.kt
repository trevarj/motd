package io.github.trevarj.motd.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.obfs.VlessLink
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface

/** Loopback endpoint returned by the embedded obfuscation core. */
data class LocalSocksEndpoint(val host: String = "127.0.0.1", val port: Int)

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
 * Owns the one embedded-core instance shared by all EMBEDDED_REALITY networks. It is synchronized
 * because Room reconciliation can build a root and its bouncer children concurrently.
 */
@Singleton
class LocalSocksProvider private constructor(private val engineFactory: () -> LocalSocksEngine) {
    @Inject constructor(@ApplicationContext context: Context) : this({ LibboxLocalSocksEngine(context) })

    private data class ActiveCore(val engine: LocalSocksEngine, val endpoint: LocalSocksEndpoint)

    // A bouncer root may be configured independently of another root. Never replace a live core
    // for A when B connects: each distinct link owns its own loopback listener until stopAll.
    private val activeCores = LinkedHashMap<VlessLink, ActiveCore>()

    companion object {
        /** Test seam: production construction is Hilt-only. */
        internal fun forTest(engineFactory: () -> LocalSocksEngine): LocalSocksProvider =
            LocalSocksProvider(engineFactory)
    }

    @Synchronized
    fun start(link: VlessLink): Result<LocalSocksEndpoint> {
        activeCores[link]?.let { return Result.success(it.endpoint) }
        val engine = engineFactory()
        return engine.start(link.toSingBoxConfigJson()).mapCatching { port ->
            require(port in 1..65535) { "Embedded SOCKS provider returned invalid port" }
            LocalSocksEndpoint(port = port).also {
                activeCores[link] = ActiveCore(engine, it)
            }
        }.onFailure { engine.stop() }
    }

    @Synchronized
    fun stop() {
        activeCores.values.forEach { it.engine.stop() }
        activeCores.clear()
    }
}

/** Direct adapter for the pinned, arm64-only libbox AAR. No generated API leaks out of service. */
private class LibboxLocalSocksEngine(context: Context) : LocalSocksEngine {
    private val appContext = context.applicationContext
    private val platform = AndroidPlatform(appContext)
    private var server: CommandServer? = null
    private var initialized = false

    @Synchronized
    override fun start(configJson: String): Result<Int> = runCatching {
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
        Libbox.setup(SetupOptions().apply {
            basePath = appContext.filesDir.absolutePath
            workingPath = appContext.filesDir.absolutePath
            tempPath = appContext.cacheDir.absolutePath
            fixAndroidStack = true
        })
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

/** SOCKS-only configuration uses no VPN, system proxy, interface monitor, or notifications. */
private object NoOpCommandServerHandler : CommandServerHandler {
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
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
    override fun next(): io.nekohasekai.libbox.NetworkInterface =
        throw NoSuchElementException("No platform network interfaces are exposed")
}

private class Strings(private val strings: List<String>) : io.nekohasekai.libbox.StringIterator {
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
private class AndroidPlatform(context: Context) : PlatformInterface {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var interfaceMonitor: io.nekohasekai.libbox.InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int,
    ): io.nekohasekai.libbox.ConnectionOwner = io.nekohasekai.libbox.ConnectionOwner()
    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator {
        val interfaces = runCatching {
            JavaNetworkInterface.getNetworkInterfaces().toList()
                .filter { network -> network.isUp }
                .map(::toLibboxInterface)
        }.getOrElse { emptyList() }
        return if (interfaces.isEmpty()) EmptyNetworkInterfaces else NetworkInterfaces(interfaces.iterator())
    }
    override fun includeAllNetworks() = false
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null
    override fun openTun(options: io.nekohasekai.libbox.TunOptions?): Int =
        throw UnsupportedOperationException("libbox TUN is not enabled by MOTD")
    // sing-box's Android platform implementation returns an object even when Wi-Fi is absent.
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState =
        io.nekohasekai.libbox.WIFIState("", "")
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) = Unit
    @Synchronized
    override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener?) {
        closeDefaultInterfaceMonitor(interfaceMonitor)
        interfaceMonitor = listener
        if (listener == null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reportDefaultInterface()
            override fun onLost(network: Network) = reportDefaultInterface()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                reportDefaultInterface()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                reportDefaultInterface()
        }
        networkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
        reportDefaultInterface()
    }
    override fun systemCertificates(): io.nekohasekai.libbox.StringIterator = EmptyStrings
    override fun underNetworkExtension() = false
    override fun usePlatformAutoDetectInterfaceControl() = false
    override fun useProcFS() = false

    private fun reportDefaultInterface() {
        val listener = interfaceMonitor ?: return
        val network = connectivity.activeNetwork
        val properties = network?.let(connectivity::getLinkProperties)
        val name = properties?.interfaceName.orEmpty()
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val expensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val constrained = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
        listener.updateDefaultInterface(name, index, expensive, constrained)
    }

    private fun toLibboxInterface(network: JavaNetworkInterface): io.nekohasekai.libbox.NetworkInterface =
        io.nekohasekai.libbox.NetworkInterface().apply {
            index = network.index
            mtu = runCatching { network.mtu }.getOrDefault(0)
            name = network.name
            // libbox maps these with netip.MustParsePrefix. Supplying a bare host address
            // panics from its network-monitor callback after the service has started.
            addresses = Strings(network.interfaceAddresses.map { address -> address.toLibboxPrefix() })
            flags = 0
            type = network.name.interfaceType()
            setDNSServer(EmptyStrings)
            metered = false
        }

    private fun String.interfaceType(): Int = when {
        startsWith("wlan") || startsWith("wifi") -> Libbox.InterfaceTypeWIFI
        startsWith("rmnet") || startsWith("ccmni") -> Libbox.InterfaceTypeCellular
        startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    private fun InterfaceAddress.toLibboxPrefix(): String {
        // A scoped IPv6 host string (for example, fe80::1%wlan0) is not accepted by
        // netip.ParsePrefix. This is the same scope-stripping conversion used upstream.
        val host = if (address is Inet6Address) {
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
internal fun VlessLink.toSingBoxConfigJson(): String = Json.encodeToString(
    kotlinx.serialization.json.JsonObject.serializer(),
    buildJsonObject {
        put("inbounds", buildJsonArray {
            add(buildJsonObject {
                put("type", "socks")
                put("listen", "127.0.0.1")
                put("listen_port", 0)
            })
        })
        put("outbounds", buildJsonArray {
            add(Json.parseToJsonElement(toSingBoxOutboundJson()))
        })
    },
)

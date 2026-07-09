package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ConnectionManagerImpl
import javax.inject.Singleton

/**
 * IRC/service seam wiring. [ConnectionManager] → [ConnectionManagerImpl] (WP5). The
 * [IrcEventSink] binding lives in [AppModule] (EventProcessor).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IrcModule {
    @Binds @Singleton
    abstract fun connectionManager(impl: ConnectionManagerImpl): ConnectionManager

    companion object {
        /**
         * Base JVM transport factory (plain okio-over-Socket/SSLSocket). ConnectionManagerImpl
         * builds a per-network TLS/client-cert-aware AppTransportFactory itself; this binding
         * satisfies its injected fallback factory.
         */
        @Provides
        @Singleton
        fun transportFactory(): TransportFactory =
            // wsUrl is ignored by the pure-JVM fallback; the app builds a WSS-aware
            // AppTransportFactory per network (plans/19 §3.3).
            TransportFactory { host, port, tls, _ -> OkioLineTransport(host, port, tls) }
    }
}

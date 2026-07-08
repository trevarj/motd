package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.IrcEventSink
import javax.inject.Singleton

// IRC/service seam wiring. WP1 stub-binds ConnectionManager + IrcEventSink; WP5 rebinds.
@Suppress("DEPRECATION")
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IrcModule {
    @Binds @Singleton
    abstract fun connectionManager(impl: StubConnectionManager): ConnectionManager

    @Binds @Singleton
    abstract fun ircEventSink(impl: StubIrcEventSink): IrcEventSink

    companion object {
        // Default JVM transport factory from :irc. The app supplies TLS/client-cert config
        // via its own SSLContext in a later WP; the plain factory is fine for the skeleton.
        @Provides
        @Singleton
        fun transportFactory(): TransportFactory =
            TransportFactory { host, port, tls -> OkioLineTransport(host, port, tls) }
    }
}

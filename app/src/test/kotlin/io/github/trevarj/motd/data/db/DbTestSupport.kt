package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

// Shared helpers for Robolectric in-memory DB tests.
internal fun inMemoryDb(): MotdDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
        .allowMainThreadQueries()
        .build()
}

internal fun network(name: String = "libera"): NetworkEntity =
    NetworkEntity(
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )

internal fun buffer(
    networkId: Long,
    name: String,
    type: BufferType = BufferType.CHANNEL,
    readMarkerTime: Long? = null,
    pinned: Boolean = false,
): BufferEntity =
    BufferEntity(
        networkId = networkId,
        name = name,
        displayName = name,
        type = type,
        readMarkerTime = readMarkerTime,
        pinned = pinned,
    )

internal fun message(
    bufferId: Long,
    text: String,
    sender: String = "alice",
    serverTime: Long,
    dedupKey: String,
    kind: MessageKind = MessageKind.PRIVMSG,
    isSelf: Boolean = false,
    hasMention: Boolean = false,
    msgid: String? = null,
    pendingLabel: String? = null,
): MessageEntity =
    MessageEntity(
        bufferId = bufferId,
        msgid = msgid,
        serverTime = serverTime,
        sender = sender,
        kind = kind,
        text = text,
        isSelf = isSelf,
        hasMention = hasMention,
        pendingLabel = pendingLabel,
        dedupKey = dedupKey,
    )

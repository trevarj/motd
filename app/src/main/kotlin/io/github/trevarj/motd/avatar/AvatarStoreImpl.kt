package io.github.trevarj.motd.avatar

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AvatarStoreImpl @Inject constructor(
    private val dao: AvatarDao,
) : AvatarStore {
    override val records: Flow<List<AvatarRecord>> = dao.observeAll().map { rows ->
        rows.map { it.toRecord() }
    }

    override suspend fun upsert(networkId: Long, nick: String, account: String?, url: String) {
        val valid = validateAvatarUrl(url) ?: return
        val normalizedNick = canonicalAvatarNick(nick)
        val identity = avatarIdentity(normalizedNick, account)
        dao.replaceIdentity(
            AvatarRecordEntity(
                networkId = networkId,
                scopedIdentity = "$networkId:$identity",
                identity = identity,
                nick = normalizedNick,
                account = account?.takeUnless { it == "*" },
                url = valid,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(networkId: Long, nick: String, account: String?) {
        dao.remove(networkId, avatarIdentity(nick, account), canonicalAvatarNick(nick))
    }

    override suspend fun rename(networkId: Long, oldNick: String, newNick: String, account: String?) {
        val oldIdentity = avatarIdentity(oldNick, account)
        val existing = dao.find(networkId, oldIdentity, canonicalAvatarNick(oldNick)) ?: return
        dao.remove(networkId, oldIdentity, canonicalAvatarNick(oldNick))
        upsert(networkId, newNick, account ?: existing.account, existing.url)
    }

    override suspend fun clearNetwork(networkId: Long) = dao.clearNetwork(networkId)
    override suspend fun clearAll() = dao.clearAll()
}

private fun AvatarRecordEntity.toRecord() = AvatarRecord(
    networkId = networkId,
    identity = identity,
    nick = nick,
    account = account,
    url = url,
    updatedAt = updatedAt,
)

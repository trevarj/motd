package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AvatarStoreTest {
    private lateinit var db: AvatarDatabase
    private lateinit var store: AvatarStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AvatarDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = AvatarStoreImpl(db.avatarDao())
    }

    @After fun tearDown() = db.close()

    @Test fun persists_valid_urls_and_ignores_invalid_values() = runTest {
        store.upsert(1, "Alice", "alice-account", "https://example.com/{size}.png")
        store.upsert(1, "Mallory", null, "http://example.com/no.png")

        assertEquals(
            listOf(AvatarRecord(1, "account:alice-account", "alice", "alice-account", "https://example.com/{size}.png", 0)),
            store.records.first().map { it.copy(updatedAt = 0) },
        )
    }

    @Test fun account_and_nick_changes_rekey_without_leaving_stale_rows() = runTest {
        store.upsert(1, "Alice", null, "https://example.com/a.png")
        store.rename(1, "Alice", "Alice2", "account")

        val records = store.records.first()
        assertEquals(1, records.size)
        assertEquals("account:account", records.single().identity)
        assertEquals("alice2", records.single().nick)
    }

    @Test fun clearing_network_does_not_touch_other_networks() = runTest {
        store.upsert(1, "Alice", null, "https://example.com/a.png")
        store.upsert(2, "Alice", null, "https://example.com/b.png")
        store.clearNetwork(1)

        val records = store.records.first()
        assertEquals(listOf(2L), records.map { it.networkId })
        store.clearAll()
        assertTrue(store.records.first().isEmpty())
    }
}

package io.github.trevarj.motd.dcc

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EbooksResultCacheTest {
    @Test
    fun `resolver reads and writes owned cached result while discard leaves user files alone`() {
        val context = RuntimeEnvironment.getApplication()
        val cache = EbooksResultCache(context)
        val uri = cache.uriFor(901)
        val userFile = File.createTempFile("book-", ".epub", context.cacheDir)
        val userUri = Uri.fromFile(userFile)
        val data = byteArrayOf(80, 75, 3, 4)
        try {
            assertTrue(cache.isOwned(uri, 901))
            assertFalse(cache.isOwned(uri, 902))
            assertFalse(cache.isOwned(userUri))
            assertFalse(cache.isOwned(Uri.parse("content://saved/book")))
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
            assertArrayEquals(data, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            userFile.writeBytes(data)
            cache.discard(userUri)
            cache.discard(uri, 902)
            assertTrue(File(requireNotNull(uri.path)).exists())
            cache.discard(uri, 901)
            assertFalse(File(requireNotNull(uri.path)).exists())
            assertArrayEquals(data, userFile.readBytes())
        } finally {
            cache.discard(uri, 901)
            userFile.delete()
        }
    }

    @Test
    fun `cache refuses traversal aliases and foreign URI schemes`() {
        val context = RuntimeEnvironment.getApplication()
        val cache = EbooksResultCache(context)
        val uri = cache.uriFor(902)
        assertFalse(cache.isOwned(Uri.fromFile(File(requireNotNull(uri.path), "../901.zip"))))
        assertFalse(cache.isOwned(Uri.parse("content://${context.packageName}/ebooks-results/902.zip")))
        assertFalse(cache.isOwned(Uri.fromFile(File(context.cacheDir, "ebooks-results/0902.zip"))))
    }
}

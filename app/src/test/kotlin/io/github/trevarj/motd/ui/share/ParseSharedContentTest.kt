package io.github.trevarj.motd.ui.share

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(application = ShareGrantTestApplication::class)
class ParseSharedContentTest {
    private val uri: Uri = Uri.parse("content://media/external/images/media/42")
    private val context = ApplicationProvider.getApplicationContext<ShareGrantTestApplication>()

    private fun multipleIntent(vararg files: Uri): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.asList()))
        }

    private fun classify(intent: Intent) = runBlocking { classifyIncomingContent(context, intent) }

    private fun expectedFiles(vararg files: Uri) = IncomingContent.Share(PendingShare.Files(files.map { PendingShare.File(it, "image/*") }))

    private fun expectedError(message: String) = IncomingContent.Error(message, R.string.share_batch_error_title)

    private fun sendIntent(
        type: String? = null,
        text: String? = null,
        stream: Uri? = null,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
        }

    @Test fun plainTextShareBecomesText() {
        val share = parseSharedContent(sendIntent(type = "text/plain", text = "hello"))
        assertEquals(PendingShare.Text("hello"), share)
    }

    @Test fun streamShareBecomesFileWithDeclaredMime() {
        val share = parseSharedContent(sendIntent(type = "image/png", stream = uri))
        assertEquals(PendingShare.File(uri, "image/png"), share)
    }

    @Test fun textPlainWithBothExtrasPrefersText() {
        val share = parseSharedContent(sendIntent(type = "text/plain", text = "hello", stream = uri))
        assertEquals(PendingShare.Text("hello"), share)
    }

    @Test fun textPlainWithOnlyStreamBecomesFile() {
        val share = parseSharedContent(sendIntent(type = "text/plain", stream = uri))
        assertEquals(PendingShare.File(uri, "text/plain"), share)
    }

    @Config(sdk = [32, 33, 34])
    @Test
    fun streamListWinsOverClipDataAndPreservesOrderAndRepeats() {
        val other = Uri.parse("content://media/external/images/media/39")
        context.grantShareRead(uri, other)
        val intent =
            multipleIntent(other, uri, other).apply {
                clipData = ClipData.newRawUri("not the payload", Uri.parse("https://example.org/ignore"))
            }
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(expectedFiles(other, uri, other), classify(Intent.CREATOR.createFromParcel(parcel)))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun clipDataFallbackRequiresAFileUriAtEveryPosition() {
        val other = Uri.parse("content://media/external/images/media/39")
        context.grantShareRead(uri, other)
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                clipData = ClipData.newRawUri("files", other).apply { addItem(ClipData.Item(uri)) }
            }
        assertEquals(expectedFiles(other, uri), classify(intent))

        // No valid prefix is handed off if even one item lacks a URI.
        intent.clipData!!.addItem(ClipData.Item("not a file"))
        assertEquals(expectedError(context.getString(R.string.share_batch_error_invalid)), classify(intent))
    }

    @Test fun malformedStreamExtraNeverFallsBackToValidClipData() {
        val intent =
            multipleIntent(uri).apply {
                clipData = ClipData.newRawUri("fallback", uri)
            }
        val invalid = expectedError(context.getString(R.string.share_batch_error_invalid))
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(invalid, classify(intent))
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, Intent()))
        assertEquals(invalid, classify(intent))
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>())
        assertEquals(invalid, classify(intent))
        assertEquals(invalid, classify(Intent(Intent.ACTION_SEND_MULTIPLE)))
    }

    @Test fun remoteOrUnsupportedUriRejectsWholeBatch() {
        val unsupported = expectedError(context.getString(R.string.share_batch_error_unsupported))
        assertEquals(unsupported, classify(multipleIntent(uri, Uri.parse("file:///data/user/0/private.txt"))))
        assertEquals(unsupported, classify(multipleIntent(uri, Uri.parse("https://example.org/remote.png"))))
        assertEquals(unsupported, classify(multipleIntent(uri, Uri.parse("file://remote.example.org/tmp/file"))))
        assertEquals(unsupported, classify(multipleIntent(uri, Uri.parse("content:malformed"))))
    }

    @Test fun readFlagDoesNotAuthorizeAnUngrantedFile() {
        val privateFile = Uri.parse("content://${context.packageName}.fileprovider/private.txt")
        context.grantShareRead(uri)
        val intent = multipleIntent(uri, privateFile).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(expectedError(context.getString(R.string.share_batch_error_permission)), classify(intent))
    }

    @Test fun overlongBatchIsRejectedInsteadOfTruncated() {
        val largest = Array(16) { uri }
        context.grantShareRead(uri)
        assertEquals(expectedFiles(*largest), classify(multipleIntent(*largest)))
        assertEquals(
            expectedError(context.getString(R.string.share_batch_error_limit, 16)),
            classify(multipleIntent(*largest, uri)),
        )
    }

    @Test fun launcherIntentIsIgnored() {
        assertNull(parseSharedContent(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun nullIntentIsIgnored() {
        assertNull(parseSharedContent(null))
    }

    @Test fun sendWithoutExtrasIsIgnored() {
        assertNull(parseSharedContent(sendIntent(type = "text/plain")))
    }

    @Test fun blankTextIsIgnored() {
        assertNull(parseSharedContent(sendIntent(type = "text/plain", text = "   ")))
    }
}

/** Robolectric does not model the system's per-URI grant service; no production override is used. */
class ShareGrantTestApplication : Application() {
    private val readable = ConcurrentHashMap.newKeySet<Uri>()

    fun grantShareRead(vararg uris: Uri) {
        readable.addAll(uris.asList())
    }

    override fun checkUriPermission(
        uri: Uri,
        pid: Int,
        uid: Int,
        modeFlags: Int,
    ): Int =
        if (pid == Process.myPid() && uid == Process.myUid() && modeFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION && uri in readable) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
}

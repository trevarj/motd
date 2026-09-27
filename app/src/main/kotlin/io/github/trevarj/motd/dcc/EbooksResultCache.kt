package io.github.trevarj.motd.dcc

import android.content.Context
import android.net.Uri
import java.io.File

/** Only DCC results addressed by transfer ID live here; user-picked destinations are never deleted. */
class EbooksResultCache(
    context: Context,
) {
    private val directory = File(context.cacheDir.canonicalFile, "ebooks-results")

    fun uriFor(transferId: Long): Uri {
        require(transferId > 0) { "Invalid DCC transfer ID" }
        check(directory.canonicalFile == directory && (directory.isDirectory || directory.mkdirs())) {
            "Unable to create results cache"
        }
        return Uri.fromFile(File(directory, "$transferId.zip"))
    }

    fun isOwned(
        uri: Uri,
        transferId: Long? = null,
    ): Boolean {
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) return false
        val path = uri.path ?: return false
        val file = File(path)
        val id = file.name.removeSuffix(".zip").toLongOrNull()
        if (!file.name.endsWith(".zip") || id == null || id <= 0 || (transferId != null && id != transferId)) return false
        return directory.canonicalFile == directory && file.path == File(directory, "$id.zip").path &&
            file.canonicalFile == File(directory, "$id.zip")
    }

    fun discard(
        uri: Uri,
        transferId: Long? = null,
    ) {
        if (isOwned(uri, transferId)) File(requireNotNull(uri.path)).delete()
    }

    companion object {
        const val MAX_COMPRESSED_BYTES = 16L * 1024L * 1024L
    }
}

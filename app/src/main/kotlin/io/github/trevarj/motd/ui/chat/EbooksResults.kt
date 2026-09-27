package io.github.trevarj.motd.ui.chat

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class EbooksResult(
    val request: String,
    val filename: String,
    val size: String,
)

private const val MAX_ZIP_ENTRIES = 128
private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
private const val MAX_RESULTS = 500
private const val MAX_REQUEST_BYTES = 400
private val bookResultLine = Regex("^(![^\\s|]+ [^\\s|]+ \\| ([^|\\r\\n]+)) ::INFO:: ([0-9]+(?:\\.[0-9]+)?(?:B|KB|MB|GB|TB))$")

/** Reads only text from a user-selected results ZIP; never extracts archive paths or book files. */
fun parseEbooksResults(input: InputStream): List<EbooksResult> {
    // ponytail: small hard limits keep a hostile ZIP bounded without an archive library.
    val header = PushbackInputStream(input.buffered(), 4)
    try {
        val signature = ByteArray(4)
        var received = 0
        while (received < signature.size) {
            val count = header.read(signature, received, signature.size - received)
            if (count == -1) throw IllegalArgumentException("Invalid results ZIP file")
            received += count
        }
        if (signature[0] != 'P'.code.toByte() || signature[1] != 'K'.code.toByte() ||
            signature[2] != 3.toByte() || signature[3] != 4.toByte()
        ) {
            throw IllegalArgumentException("Invalid results ZIP file")
        }
        header.unread(signature)
        ZipInputStream(header).use { zip ->
            val results = ArrayList<EbooksResult>()
            val buffer = ByteArray(8192)
            var entries = 0
            var totalBytes = 0
            var textEntries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw IllegalArgumentException("Results ZIP has too many entries (limit $MAX_ZIP_ENTRIES)")
                if (!entry.isDirectory) {
                    val isText = entry.name.endsWith(".txt", ignoreCase = true)
                    if (isText) textEntries++
                    val text = if (isText) ByteArrayOutputStream() else null
                    var entryBytes = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count == -1) break
                        entryBytes += count
                        totalBytes += count
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw IllegalArgumentException("Results ZIP exceeds the uncompressed size limit")
                        }
                        text?.write(buffer, 0, count)
                    }
                    if (text != null) {
                        val decoded =
                            try {
                                StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(text.toByteArray()))
                                    .toString()
                            } catch (e: java.nio.charset.CharacterCodingException) {
                                throw IllegalArgumentException("Results text must be UTF-8", e)
                            }
                        decoded.splitToSequence('\n').forEach { rawLine ->
                            val line = rawLine.removeSuffix("\r")
                            val match = bookResultLine.matchEntire(line) ?: return@forEach
                            val request = match.groupValues[1]
                            val filename = match.groupValues[2]
                            if (filename.isBlank() || request.length > MAX_REQUEST_BYTES ||
                                (request.any { it.code > 127 } && request.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) ||
                                request.any { char ->
                                    val type = Character.getType(char)
                                    Character.isISOControl(char) || type == Character.FORMAT.toInt() ||
                                        type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
                                }
                            ) {
                                return@forEach
                            }
                            if (results.size == MAX_RESULTS) throw IllegalArgumentException("Results ZIP has too many book results (limit $MAX_RESULTS)")
                            results.add(EbooksResult(request, filename, match.groupValues[3]))
                        }
                    }
                }
                zip.closeEntry()
            }
            if (textEntries == 0) throw IllegalArgumentException("No .txt results entries in ZIP")
            if (results.isEmpty()) throw IllegalArgumentException("No valid book results in ZIP .txt entries")
            return results
        }
    } catch (e: ZipException) {
        throw IllegalArgumentException("Invalid results ZIP file", e)
    } finally {
        header.close()
    }
}

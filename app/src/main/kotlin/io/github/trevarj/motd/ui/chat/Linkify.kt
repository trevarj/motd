package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.isImmediateAudioUrl
import io.github.trevarj.motd.audio.parseAudioAttachments
import java.util.LinkedHashMap

/** URL detection shared by the composer/bubble. Deliberately conservative (http/https only). */
private val URL_REGEX = Regex("""https?://[^\s<>]+""")

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "webm", "m4v", "mov")

/** Ordered inline-media URLs, first previewable link, and audio links discovered in one URL-regex pass. */
data class MessageUrls(
    val mediaUrls: List<String>,
    val linkUrl: String?,
    val audio: List<AudioAttachment> = emptyList(),
) {
    companion object {
        val Empty = MessageUrls(mediaUrls = emptyList(), linkUrl = null, audio = emptyList())
    }
}

/**
 * Bounded process-lifetime parse cache. Lazy rows are routinely disposed and recreated while
 * scrolling or switching buffers, so retaining both positive and empty parses avoids repeating the
 * regex/code-segment walk before already-known rich content can be rendered.
 */
internal object MessageUrlCache {
    private const val MAX_ENTRIES = 512
    private val entries =
        object : LinkedHashMap<String, MessageUrls>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageUrls>?): Boolean = size > MAX_ENTRIES
        }

    @Synchronized
    fun get(text: String): MessageUrls? = entries[text]

    @Synchronized
    fun put(
        text: String,
        urls: MessageUrls,
    ) {
        entries[text] = urls
    }

    @Synchronized
    internal fun clearForTest() {
        entries.clear()
    }
}

/** All http(s) URLs in [text], in order of appearance. */
fun extractUrls(text: String): List<String> =
    parseInlineCode(text)
        .asSequence()
        .filterIsInstance<InlineTextSegment.Plain>()
        .flatMap { segment -> URL_REGEX.findAll(segment.text).map { trimUrl(it.value) } }
        .toList()

/**
 * Trim trailing punctuation that commonly abuts a URL in prose. A closing `)` is only stripped when
 * the URL contains no matching `(` — otherwise Wikipedia-style paths like `…/Foo_(bar)` would break
 *. Brackets/braces get the same balance check.
 */
internal fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        val strip =
            when (last) {
                '.', ',', '!', '?' -> true
                ')' -> url.count { it == '(' } < url.count { it == ')' }
                ']' -> url.count { it == '[' } < url.count { it == ']' }
                '}' -> url.count { it == '{' } < url.count { it == '}' }
                else -> false
            }
        if (!strip) break
        url = url.dropLast(1)
    }
    return url
}

/** True when [url]'s path ends in a known image extension. */
fun isImageUrl(url: String): Boolean = urlExtension(url) in IMAGE_EXT

/** True when [url]'s path ends in a directly playable video extension. */
fun isVideoUrl(url: String): Boolean = urlExtension(url) in VIDEO_EXT

private fun urlExtension(url: String): String =
    url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase()

/** First non-inline-media URL in [text], or null (used for the link preview card). */
fun firstLinkUrl(text: String): String? = messageUrls(text).linkUrl

/**
 * Resolve rich-content URLs in one pass. Chat rows call this off the UI thread once scrolling
 * is idle, avoiding separate regex walks for every row first composed during a fling.
 */
fun messageUrls(text: String): MessageUrls {
    val media = mutableListOf<String>()
    var link: String? = null
    // WebM can carry either format. An explicit video suffix belongs to the inline video preview,
    // rather than creating competing audio and video controls for the same attachment.
    val audio = parseAudioAttachments(text).filterNot { isVideoUrl(it.url) }
    val audioUrls = audio.asSequence().map { it.url }.toSet()
    for (url in extractUrls(text)) {
        if (isImageUrl(url) || isVideoUrl(url)) {
            media.add(url)
        } else if (link == null && url !in audioUrls && !isImmediateAudioUrl(url)) {
            link = url
        }
    }
    return if (media.isEmpty() && link == null && audio.isEmpty()) {
        MessageUrls.Empty
    } else {
        MessageUrls(media, link, audio)
    }
}

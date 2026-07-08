package io.github.trevarj.motd.ui.chat

/** URL detection shared by the composer/bubble. Deliberately conservative (http/https only). */
private val URL_REGEX = Regex("""https?://[^\s<>]+""")

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/** All http(s) URLs in [text], in order of appearance. */
fun extractUrls(text: String): List<String> =
    URL_REGEX.findAll(text).map { trimUrl(it.value) }.toList()

/**
 * Trim trailing punctuation that commonly abuts a URL in prose. A closing `)` is only stripped when
 * the URL contains no matching `(` — otherwise Wikipedia-style paths like `…/Foo_(bar)` would break
 * (plans/15 #29). Brackets/braces get the same balance check.
 */
private fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
        val last = url.last()
        val strip = when (last) {
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
fun isImageUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXT
}

/** First image URL in [text], or null. */
fun firstImageUrl(text: String): String? = extractUrls(text).firstOrNull { isImageUrl(it) }

/** First non-image URL in [text], or null (used for the link preview card). */
fun firstLinkUrl(text: String): String? = extractUrls(text).firstOrNull { !isImageUrl(it) }

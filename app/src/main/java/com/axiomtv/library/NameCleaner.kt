package com.axiomtv.library

/**
 * Tidies the names IPTV providers use, e.g.
 *   "EN | Dune: Part Two (2024) [4K]"  ->  "Dune: Part Two", year 2024, quality "4K"
 *   "UK ~ Entertainment"               ->  "Entertainment" (categories)
 */
object NameCleaner {
    data class Cleaned(
        val name: String,
        val year: Int?,
        val quality: String?,
    )

    private val prefix =
        Regex("""^\s*(\[[^\]]{1,12}\]|\|[^|]{1,12}\||[A-Z]{2,3}(?:-[A-Z]{2,3})?\s*[|~:\-–]\s+)\s*""")
    private val qualityTag =
        Regex("""(?i)[\[(]?\b(4K|UHD|2160p|1080p|720p|FHD|HD|SD|HEVC|H265|HDR10\+?|HDR|DV|DOLBY VISION|MULTI|MULTISUB|VOSTFR)\b[\])]?""")
    private val yearInBrackets = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val trailingYear = Regex("""\s[-–]?\s*((?:19|20)\d{2})\s*$""")
    private val spaces = Regex("""\s{2,}""")

    fun clean(raw: String): Cleaned {
        var text = raw.trim()
        // Strip up to two leading language/provider tags ("EN | ", "|UK| ", "[EN] ")
        repeat(2) { text = text.replace(prefix, "") }

        var quality: String? = null
        qualityTag.find(text)?.let { match ->
            quality =
                when (val tag = match.groupValues[1].uppercase()) {
                    "UHD", "2160P" -> "4K"
                    "FHD", "1080P" -> "1080p"
                    "720P" -> "720p"
                    else -> tag
                }
        }

        var year: Int? = null
        yearInBrackets.find(text)?.let {
            year = it.groupValues[1].toIntOrNull()
            text = text.removeRange(it.range)
        }
        text = text.replace(qualityTag, " ")
        if (year == null) {
            trailingYear.find(text)?.let {
                year = it.groupValues[1].toIntOrNull()
                text = text.removeRange(it.range)
            }
        }
        text = text.replace(spaces, " ").trim().trim('-', '–', '|', ':', '~').trim()
        if (text.isEmpty()) text = raw.trim()
        return Cleaned(text, year, quality)
    }

    /** Category names: "UK ~ Entertainment" -> "Entertainment", "VOD | ACTION" -> "Action". */
    fun category(raw: String): String {
        var text = raw.trim()
        repeat(2) { text = text.replace(prefix, "") }
        text = text.replace(spaces, " ").trim()
        if (text.isEmpty()) return raw.trim()
        val isShouting = text.length > 3 && text == text.uppercase() && text.any { it.isLetter() }
        return if (isShouting) {
            text.lowercase().split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        } else {
            text
        }
    }
}

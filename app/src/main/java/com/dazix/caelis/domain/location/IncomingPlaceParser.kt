package com.dazix.caelis.domain.location

import com.dazix.caelis.domain.solar.CoordinateParseResult
import com.dazix.caelis.domain.solar.GeoCoordinateParser
import com.dazix.caelis.domain.solar.GeoLocation

/**
 * What another app handed over: a shared text (Mapy.com, Google Maps, a message with coordinates)
 * or a `geo:` link.
 */
sealed interface IncomingPlace {
    /** A name the sender offered (subject, a `geo:` label or the first plain line), or null. */
    val suggestedName: String?

    data class Found(val location: GeoLocation, override val suggestedName: String?) : IncomingPlace

    /**
     * A shortened map link (`mapy.com/s/…`, `maps.app.goo.gl/…`). The coordinates are only on the
     * server it redirects to, and following it would be a second network caller that tells that
     * server which place the user is looking at; the user is asked to share the coordinates instead.
     */
    data class ShortLink(override val suggestedName: String?) : IncomingPlace

    /** Nothing in the text reads as a position. */
    data class NotFound(override val suggestedName: String?) : IncomingPlace
}

/**
 * Reads a position out of whatever a share or a `geo:` link carried. Pure and offline: it never
 * resolves anything, it only reads what is in the text.
 */
object IncomingPlaceParser {

    /** Longer than any real place name; a shared paragraph is not a name. */
    const val MAX_NAME_LENGTH = 60

    private val URL_TOKEN = Regex("""(?i)(?:https?://|geo:)\S+""")
    private val SHORT_LINK = Regex("""(?i)(?:mapy\.(?:cz|com)/s/|maps\.app\.goo\.gl/|goo\.gl/maps/)""")
    private val GEO_LABEL = Regex("""(?i)^geo:[^?]*\?(?:.*&)?q=[^&(]*\(([^)]+)\)""")

    fun parse(text: String?, subject: String? = null): IncomingPlace {
        val body = text?.trim().orEmpty()
        val urls = URL_TOKEN.findAll(body).map { trimLink(it.value) }.toList()
        val name = suggestName(body, subject, urls)

        // Links first: their numbers are coordinates by construction, a line of prose may hold others.
        val candidates = urls + body.lines().map { it.trim() }.filter { it.isNotEmpty() } + body
        for (candidate in candidates) {
            val result = GeoCoordinateParser.parse(candidate)
            if (result is CoordinateParseResult.Success) return IncomingPlace.Found(result.location, name)
        }
        return if (urls.any { SHORT_LINK.containsMatchIn(it) }) {
            IncomingPlace.ShortLink(name)
        } else {
            IncomingPlace.NotFound(name)
        }
    }

    /**
     * Punctuation of the sentence a link stands in is not part of it. A closing parenthesis is,
     * when it closes one the link opened: a geo: label "(Sněžka)".
     */
    private fun trimLink(token: String): String {
        var link = token.trimEnd('.', ',', ';')
        while (link.endsWith(')') && link.count { it == ')' } > link.count { it == '(' }) {
            link = link.dropLast(1).trimEnd('.', ',', ';')
        }
        return link
    }

    private fun suggestName(body: String, subject: String?, urls: List<String>): String? {
        val geoLabel = urls.firstNotNullOfOrNull { url ->
            GEO_LABEL.find(decode(url))?.groupValues?.get(1)
        }
        val firstPlainLine = body.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() &&
                    !URL_TOKEN.containsMatchIn(line) &&
                    GeoCoordinateParser.parse(line) !is CoordinateParseResult.Success
            }
        return listOfNotNull(subject, geoLabel, firstPlainLine)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > MAX_NAME_LENGTH) it.take(MAX_NAME_LENGTH).trimEnd() + "…" else it }
    }

    /**
     * Percent-decoding, UTF-8 aware ("Sn%C4%9B%C5%BEka" is "Sněžka"); a malformed escape is kept as
     * text. Not the JDK's URLDecoder: `NetworkIsolationTest` refuses its package outside the
     * downloader, and a decoder is not worth an exception to that rule.
     */
    internal fun decode(url: String): String {
        val bytes = java.io.ByteArrayOutputStream(url.length)
        var i = 0
        while (i < url.length) {
            val c = url[i]
            val hex = if (c == '%' && i + 2 <= url.lastIndex) {
                url.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            when {
                hex != null -> {
                    bytes.write(hex)
                    i += 3
                }
                c == '+' -> {
                    bytes.write(' '.code)
                    i++
                }
                else -> {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < url.length) i + 2 else i + 1
                    bytes.write(url.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}

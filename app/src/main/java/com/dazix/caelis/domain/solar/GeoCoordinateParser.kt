package com.dazix.caelis.domain.solar

/**
 * Coordinate notation format detected during parsing.
 */
enum class CoordinateFormat {
    DECIMAL_DEGREES,
    DEGREES_MINUTES_SECONDS,
    DEGREES_DECIMAL_MINUTES,
    MAP_LINK,
}

/**
 * Outcome of parsing a geographic coordinate input.
 */
sealed interface CoordinateParseResult {
    data class Success(
        val location: GeoLocation,
        val format: CoordinateFormat,
    ) : CoordinateParseResult

    sealed interface Failure : CoordinateParseResult {
        data object Empty : Failure
        data class OutOfBounds(val latitude: Double, val longitude: Double) : Failure
        data class InvalidFormat(val rawInput: String) : Failure
    }
}

/**
 * Pure Kotlin parser for geographic coordinates supporting multiple real-world notations:
 * - Decimal Degrees (DD): e.g. "50.0755, 14.4378", "-22.9068, -43.1729", "50.0755° N, 14.4378° E"
 * - Degrees Minutes Seconds (DMS): e.g. "50° 4' 31.8\" N, 14° 26' 16.1\" E", "50°04'31\"N 14°26'16\"E"
 * - Degrees Decimal Minutes (DDM): e.g. "50° 04.530' N, 14° 26.268' E", "N 50 04.530, E 014 26.268"
 * - Map URLs & Geo URIs: Google Maps, Mapy.cz, OpenStreetMap, Apple Maps, geo: URIs
 */
object GeoCoordinateParser {

    private val MAP_URL_REGEX = Regex("""(?i)(?:https?://|geo:|maps\.google|mapy\.cz|openstreetmap\.org)""")
    private val GOOGLE_AT_REGEX = Regex("""@(-?\d+\.?\d*),(-?\d+\.?\d*)""")
    private val QUERY_PARAM_LAT_LON = Regex("""[?&](?:q|query|ll|loc|mlat)=(-?\d+\.?\d*)(?:[,%2C\s]+|&mlon=)(-?\d+\.?\d*)""")
    private val MAPY_CZ_XY_REGEX = Regex("""[?&]y=(-?\d+\.?\d*)&x=(-?\d+\.?\d*)|[?&]x=(-?\d+\.?\d*)&y=(-?\d+\.?\d*)""")
    private val OSM_MAP_HASH_REGEX = Regex("""#map=\d+/(-?\d+\.?\d*)/(-?\d+\.?\d*)""")
    private val GEO_URI_REGEX = Regex("""geo:(-?\d+\.?\d*),(-?\d+\.?\d*)""")

    /**
     * Parses an input string into a [CoordinateParseResult].
     */
    fun parse(input: String?): CoordinateParseResult {
        if (input.isNullOrBlank()) return CoordinateParseResult.Failure.Empty
        val trimmed = input.trim()

        // 1. Try Map URLs and URIs
        if (MAP_URL_REGEX.containsMatchIn(trimmed)) {
            val fromUrl = parseMapUrl(trimmed)
            if (fromUrl != null) return fromUrl
        }

        // 2. Split into two coordinate parts
        val parts = splitCoordinateParts(trimmed)
        if (parts.size != 2) return CoordinateParseResult.Failure.InvalidFormat(trimmed)

        val (rawPart1, rawPart2) = parts
        val p1 = parseSingleCoordinate(rawPart1) ?: return CoordinateParseResult.Failure.InvalidFormat(trimmed)
        val p2 = parseSingleCoordinate(rawPart2) ?: return CoordinateParseResult.Failure.InvalidFormat(trimmed)

        // Assign latitude vs longitude based on explicit hemisphere if present
        val (lat, lon) = when {
            p1.isLatitude == true && p2.isLatitude == false -> p1.value to p2.value
            p1.isLatitude == false && p2.isLatitude == true -> p2.value to p1.value
            else -> p1.value to p2.value // Default order: Lat, Lon
        }

        // Determine combined format
        val format = when {
            p1.format == CoordinateFormat.DEGREES_MINUTES_SECONDS || p2.format == CoordinateFormat.DEGREES_MINUTES_SECONDS ->
                CoordinateFormat.DEGREES_MINUTES_SECONDS
            p1.format == CoordinateFormat.DEGREES_DECIMAL_MINUTES || p2.format == CoordinateFormat.DEGREES_DECIMAL_MINUTES ->
                CoordinateFormat.DEGREES_DECIMAL_MINUTES
            else ->
                CoordinateFormat.DECIMAL_DEGREES
        }

        return validateAndCreate(lat, lon, format)
    }

    private data class SingleParsed(
        val value: Double,
        val isLatitude: Boolean?,
        val format: CoordinateFormat,
    )

    private fun splitCoordinateParts(text: String): List<String> {
        val clean = text.trim()

        // 1. Semicolon delimiter (e.g. "50.0755; 14.4378" or "50,0755; 14,4378")
        if (clean.contains(";")) {
            val split = clean.split(";")
            if (split.size == 2 && split[0].isNotBlank() && split[1].isNotBlank()) {
                return split.map { it.trim() }
            }
        }

        // 2. Comma followed by space (e.g. "50.0755, 14.4378" or "50,0755, 14,4378" or "50° 4' 31.8\" N, 14° 26' 16.1\" E")
        val commaSpaceSplit = clean.split(Regex(""",\s+"""))
        if (commaSpaceSplit.size == 2 && commaSpaceSplit[0].isNotBlank() && commaSpaceSplit[1].isNotBlank()) {
            return commaSpaceSplit.map { it.trim() }
        }

        // 3. Hemisphere boundary split (e.g. "50°04'31.8"N 14°26'16.1"E" or "50.0755N 14.4378E")
        val hemiSplit = clean.split(Regex("""(?<=[NSEWnsew])\s+"""))
        if (hemiSplit.size == 2 && hemiSplit[0].isNotBlank() && hemiSplit[1].isNotBlank()) {
            return hemiSplit.map { it.trim() }
        }

        // 4. Standard comma (without space) if each side has 1 dot decimal: "50.0755,14.4378"
        if (clean.contains(",")) {
            val commaParts = clean.split(",")
            if (commaParts.size == 2 && commaParts[0].isNotBlank() && commaParts[1].isNotBlank()) {
                return commaParts.map { it.trim() }
            }
        }

        // 5. Space separated when no other commas: "50.0755 14.4378" or "50,0755 14,4378"
        val spaceParts = clean.split(Regex("""\s+"""))
        if (spaceParts.size == 2 && spaceParts[0].isNotBlank() && spaceParts[1].isNotBlank()) {
            return spaceParts.map { it.trim() }
        }

        return emptyList()
    }

    private fun parseSingleCoordinate(raw: String): SingleParsed? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1. Extract hemisphere (N, S, E, W) and sign
        var sign = 1.0
        var isLatitude: Boolean? = null

        val upper = trimmed.uppercase()
        val hasN = upper.contains("N")
        val hasS = upper.contains("S")
        val hasE = upper.contains("E")
        val hasW = upper.contains("W")

        if (hasS) {
            sign = -1.0
            isLatitude = true
        } else if (hasN) {
            sign = 1.0
            isLatitude = true
        } else if (hasW) {
            sign = -1.0
            isLatitude = false
        } else if (hasE) {
            sign = 1.0
            isLatitude = false
        }

        if (trimmed.startsWith("-") || trimmed.startsWith("−")) {
            sign = -sign
        }

        // Strip hemisphere letters and leading signs
        val cleanNumberStr = trimmed
            .replace(Regex("""(?i)[NSEW]"""), " ")
            .replace(Regex("""^[+\-−]\s*"""), "")
            .trim()

        // 2. Check DMS: 3 numbers (D, M, S)
        // Must have delimiter (symbol or whitespace) between D and M, and between M and S.
        // e.g. "50° 4' 31.8\"", "50°04'31.8\"", "50 4 31.8", "50d 4m 31.8s"
        val dmsWithSymbols = Regex(
            """^(\d{1,3})\s*(?:[°d]|deg)\s*(\d{1,2})\s*(?:['′’m]|min)\s*(\d{1,2}(?:[.,]\d+)?)\s*(?:["″”'']|sec|s)?$""",
            RegexOption.IGNORE_CASE
        ).find(cleanNumberStr)

        val dmsWithSpaces = Regex(
            """^(\d{1,3})\s+(\d{1,2})\s+(\d{1,2}(?:[.,]\d+)?)\s*(?:["″”'']|sec|s)?$""",
            RegexOption.IGNORE_CASE
        ).find(cleanNumberStr)

        val dmsMatch = dmsWithSymbols ?: dmsWithSpaces
        if (dmsMatch != null) {
            val d = dmsMatch.groupValues[1].toDoubleOrNull() ?: return null
            val m = dmsMatch.groupValues[2].toDoubleOrNull() ?: return null
            val s = dmsMatch.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
            val decimal = (d + (m / 60.0) + (s / 3600.0)) * sign
            return SingleParsed(decimal, isLatitude, CoordinateFormat.DEGREES_MINUTES_SECONDS)
        }

        // 3. Check DDM: 2 numbers (D, decimal M)
        // e.g. "50° 04.530'", "50°04.530'", "50 04.530"
        val ddmWithSymbols = Regex(
            """^(\d{1,3})\s*(?:[°d]|deg)\s*(\d{1,2}(?:[.,]\d+)?)\s*(?:['′’m]|min)?$""",
            RegexOption.IGNORE_CASE
        ).find(cleanNumberStr)

        val ddmWithSpaces = Regex(
            """^(\d{1,3})\s+(\d{1,2}(?:[.,]\d+))\s*(?:['′’m]|min)?$""",
            RegexOption.IGNORE_CASE
        ).find(cleanNumberStr)

        val ddmMatch = ddmWithSymbols ?: ddmWithSpaces
        if (ddmMatch != null) {
            val d = ddmMatch.groupValues[1].toDoubleOrNull() ?: return null
            val m = ddmMatch.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            val decimal = (d + (m / 60.0)) * sign
            return SingleParsed(decimal, isLatitude, CoordinateFormat.DEGREES_DECIMAL_MINUTES)
        }

        // 4. Check DD (Decimal Degrees): single number
        // e.g. "50.0755", "50,0755", "50.0755°"
        val ddMatch = Regex("""^(\d+(?:[.,]\d+)?)\s*(?:[°d]|deg)?$""", RegexOption.IGNORE_CASE)
            .find(cleanNumberStr)
        if (ddMatch != null) {
            val d = ddMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val decimal = d * sign
            return SingleParsed(decimal, isLatitude, CoordinateFormat.DECIMAL_DEGREES)
        }

        return null
    }

    private fun parseMapUrl(url: String): CoordinateParseResult? {
        val decoded = decodePercent(url)

        // Check geo URI
        GEO_URI_REGEX.find(decoded)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return validateAndCreate(lat, lon, CoordinateFormat.MAP_LINK)
        }

        // Check Google Maps @lat,lon
        GOOGLE_AT_REGEX.find(decoded)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return validateAndCreate(lat, lon, CoordinateFormat.MAP_LINK)
        }

        // Check Mapy.cz y=lat&x=lon or x=lon&y=lat
        MAPY_CZ_XY_REGEX.find(decoded)?.let { m ->
            val lat = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toDoubleOrNull() ?: return null
            val lon = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toDoubleOrNull() ?: return null
            return validateAndCreate(lat, lon, CoordinateFormat.MAP_LINK)
        }

        // Check OSM #map=zoom/lat/lon
        OSM_MAP_HASH_REGEX.find(decoded)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return validateAndCreate(lat, lon, CoordinateFormat.MAP_LINK)
        }

        // Check query params (?q=lat,lon)
        QUERY_PARAM_LAT_LON.find(decoded)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            return validateAndCreate(lat, lon, CoordinateFormat.MAP_LINK)
        }

        // If URL contained query with coordinate text (e.g. q=50°4'31"N 14°26'16"E)
        val queryIdx = decoded.indexOfAny(charArrayOf('?', '&', '#'))
        if (queryIdx >= 0) {
            val queryPart = decoded.substring(queryIdx + 1)
            val paramVal = queryPart.substringAfter("q=").substringBefore('&')
                .ifEmpty { queryPart.substringAfter("query=").substringBefore('&') }
            if (paramVal.isNotBlank() && paramVal != queryPart) {
                val subResult = parse(paramVal)
                if (subResult is CoordinateParseResult.Success) {
                    return CoordinateParseResult.Success(subResult.location, CoordinateFormat.MAP_LINK)
                }
            }
        }

        return null
    }

    private fun decodePercent(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    sb.append(byteVal.toChar())
                    i += 3
                    continue
                }
            } else if (c == '+') {
                sb.append(' ')
                i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun validateAndCreate(lat: Double, lon: Double, format: CoordinateFormat): CoordinateParseResult {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return CoordinateParseResult.Failure.OutOfBounds(lat, lon)
        }
        return CoordinateParseResult.Success(GeoLocation(lat, lon), format)
    }
}

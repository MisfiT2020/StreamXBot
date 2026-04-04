package com.xstream.music.data.api

import com.xstream.music.data.model.ArtistByIdResponse
import com.xstream.music.data.model.ArtistItem
import com.xstream.music.data.model.ArtistLink
import com.xstream.music.data.model.ArtistMember
import com.xstream.music.data.model.ArtistResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

private const val SHAZAM_BASE_URL = "https://www.shazam.com"
private val APPLE_IMAGE_SIZE_REGEX = Regex("""/(\d+)x(\d+)(bb)?\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)
private val ITUNES_M3U8_REGEX =
    Regex("""(https://mvod\.itunes\.apple\.com/itunes-assets/[^\s"']+?/)(P\d+)_default\.m3u8""", RegexOption.IGNORE_CASE)
private val M3U8_URL_REGEX = Regex("""https://[^\s"'`<>]+?\.m3u8""", RegexOption.IGNORE_CASE)
private val YEAR_FROM_DESCRIPTION_PATTERNS = listOf(
    Regex("""\bformed\s+(?:in\s+)?(\d{4})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bbrought together in\s+(\d{4})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bestablished\s+(?:in\s+)?(\d{4})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bfounded\s+(?:in\s+)?(\d{4})\b""", RegexOption.IGNORE_CASE),
    Regex("""\bformed\s+around\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
)

private data class ShazamArtistDetail(
    val id: String? = null,
    val href: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val videoPosterUrl: String? = null,
    val videoHlsUrl: String? = null,
    val genres: List<String>? = null,
    val description: String? = null,
    val hometown: String? = null,
    val born: String? = null,
    val formed: String? = null,
    val links: List<ArtistLink>? = null,
    val members: List<ArtistMember>? = null,
    val memberOf: List<ArtistMember>? = null
)

suspend fun fetchArtists(query: String, limit: Int = 1, includePage: Boolean = true): ArtistResponse? = withContext(Dispatchers.IO) {
    val term = query.trim()
    if (term.isBlank()) return@withContext ArtistResponse(ok = true, items = emptyList())

    val requestedLimit = limit.coerceIn(1, 25)
    val fetchLimit = max(requestedLimit, 10)
    val country = shazamCountry()
    val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
    val searchUrl = "$SHAZAM_BASE_URL/services/amapi/v1/catalog/${country.lowercase(Locale.US)}/search" +
        "?types=artists&term=$encodedTerm&limit=$fetchLimit"

    try {
        val rawPayload = fetchShazamJson(searchUrl) ?: return@withContext null
        val rankedNodes = extractArtistSearchItems(rawPayload)
            .map { node ->
                val attrs = node.optJSONObject("attributes")
                val name = pickFirstString(attrs?.opt("name")) ?: pickFirstString(node.opt("name")).orEmpty()
                artistQueryScore(term, name) to node
            }
            .sortedByDescending { it.first }
            .take(requestedLimit)

        val items = rankedNodes.mapNotNull { (_, node) ->
            hydrateArtistNode(
                node = node,
                requestedLimit = requestedLimit,
                includePage = includePage,
                query = term
            )
        }.sortedByDescending { artistQueryScore(term, it.name) }

        ArtistResponse(ok = true, items = items)
    } catch (e: Exception) {
        Timber.e(e, "Error fetching artists directly from Shazam")
        null
    }
}

suspend fun fetchArtistById(artistId: String, includePage: Boolean = true, slug: String? = null): ArtistByIdResponse? = withContext(Dispatchers.IO) {
    val sid = artistId.trim()
    if (!sid.matches(Regex("""\d+"""))) return@withContext null

    val country = shazamCountry()
    val href = "/v1/catalog/${country.lowercase(Locale.US)}/artists/$sid"

    try {
        val detail = parseShazamArtistDetailJson(fetchShazamJson("$SHAZAM_BASE_URL/services/amapi$href"))
        val htmlMeta = if (includePage) {
            val pageSlug = slug?.trim()?.takeIf { it.isNotBlank() }?.let(::shazamArtistSlug) ?: "x"
            parseShazamArtistHtml(fetchShazamHtml("$SHAZAM_BASE_URL/artist/$pageSlug/$sid").orEmpty())
        } else {
            ShazamArtistDetail()
        }

        val name = detail.name ?: htmlMeta.name ?: sid
        val description = detail.description ?: htmlMeta.description
        val formed = detail.formed ?: htmlMeta.formed ?: inferFormed(description)
        val members = if (includePage) enrichMembersWithImages(htmlMeta.members.orEmpty()) else htmlMeta.members

        val item = ArtistItem(
            id = sid,
            name = name,
            image_url = resizeAppleImageUrl(detail.imageUrl ?: htmlMeta.imageUrl, size = 618),
            video_poster_url = cleanUrl(htmlMeta.videoPosterUrl),
            video_hls_url = cleanUrl(htmlMeta.videoHlsUrl),
            genres = detail.genres ?: htmlMeta.genres,
            description = description,
            hometown = detail.hometown ?: htmlMeta.hometown,
            born = detail.born ?: htmlMeta.born,
            formed = formed,
            links = htmlMeta.links,
            members = members,
            member_of = htmlMeta.memberOf
        )

        ArtistByIdResponse(ok = true, id = sid, item = item)
    } catch (e: Exception) {
        Timber.e(e, "Error fetching artist detail directly from Shazam: $sid")
        null
    }
}

private suspend fun hydrateArtistNode(
    node: JSONObject,
    requestedLimit: Int,
    includePage: Boolean,
    query: String
): ArtistItem? {
    var sid = pickFirstString(node.opt("id"))
    var href = pickFirstString(node.opt("href"))
    val detail = if (!href.isNullOrBlank()) {
        parseShazamArtistDetailJson(fetchShazamJson("$SHAZAM_BASE_URL/services/amapi$href"))
    } else {
        ShazamArtistDetail()
    }

    val attrs = node.optJSONObject("attributes")
    val name = detail.name ?: pickFirstString(attrs?.opt("name")) ?: pickFirstString(node.opt("name"))
    if (sid.isNullOrBlank()) sid = detail.id
    if (href.isNullOrBlank()) href = detail.href
    if (sid.isNullOrBlank() || name.isNullOrBlank()) return null

    var shouldFetchPage = includePage && requestedLimit <= 5
    if (includePage && !shouldFetchPage) {
        if (detail.hometown.isNullOrBlank() || detail.formed.isNullOrBlank()) {
            shouldFetchPage = true
        }
        if (detail.imageUrl.isNullOrBlank() || detail.description.isNullOrBlank() || detail.genres.isNullOrEmpty()) {
            shouldFetchPage = true
        }
    }

    val htmlMeta = if (shouldFetchPage) {
        val pageUrl = "$SHAZAM_BASE_URL/artist/${shazamArtistSlug(name)}/$sid"
        parseShazamArtistHtml(fetchShazamHtml(pageUrl).orEmpty())
    } else {
        ShazamArtistDetail()
    }

    val description = detail.description ?: htmlMeta.description
    val formed = detail.formed ?: htmlMeta.formed ?: inferFormed(description)
    val members = if (shouldFetchPage) enrichMembersWithImages(htmlMeta.members.orEmpty()) else htmlMeta.members

    return ArtistItem(
        id = sid,
        name = name,
        image_url = resizeAppleImageUrl(detail.imageUrl ?: htmlMeta.imageUrl, size = 618),
        video_poster_url = cleanUrl(htmlMeta.videoPosterUrl),
        video_hls_url = cleanUrl(htmlMeta.videoHlsUrl),
        genres = detail.genres ?: htmlMeta.genres,
        description = description,
        hometown = detail.hometown ?: htmlMeta.hometown,
        born = detail.born ?: htmlMeta.born,
        formed = formed,
        links = htmlMeta.links,
        members = members,
        member_of = htmlMeta.memberOf
    )
}

private fun shazamCountry(): String {
    val raw = Locale.getDefault().country?.trim()?.uppercase(Locale.US).orEmpty()
    return if (raw.length == 2 || raw.length == 3) raw else "IN"
}

private fun fetchShazamJson(url: String): Any? {
    val connection = openShazamConnection(url, accept = "application/json")
    return try {
        if (connection.responseCode !in 200..299) return null
        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        JSONTokener(responseText).nextValue()
    } finally {
        connection.disconnect()
    }
}

private fun fetchShazamHtml(url: String): String? {
    val connection = openShazamConnection(
        url = url,
        accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )
    return try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun openShazamConnection(url: String, accept: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        setRequestProperty("Accept", accept)
        setRequestProperty("Referer", "$SHAZAM_BASE_URL/")
    }

private fun parseShazamArtistDetailJson(payload: Any?): ShazamArtistDetail {
    val dataObject = when (payload) {
        is JSONObject -> {
            when (val data = payload.opt("data")) {
                is JSONArray -> data.optJSONObject(0)
                is JSONObject -> data
                else -> null
            }
        }
        else -> null
    } ?: return ShazamArtistDetail()

    val attrs = dataObject.optJSONObject("attributes")
    val genres = attrs?.optJSONArray("genreNames")?.toStringList()
    val notes = attrs?.optJSONObject("editorialNotes")
    val description = pickFirstString(notes?.opt("standard")) ?: pickFirstString(notes?.opt("short"))
    val born = pickFirstString(attrs?.opt("dateOfBirth"))
        ?: pickFirstString(attrs?.opt("born"))
        ?: pickFirstString(attrs?.opt("birthDate"))
    val formed = pickFirstString(attrs?.opt("bornOrFormed"))
        ?: pickFirstString(attrs?.opt("born_or_formed"))
        ?: pickFirstString(attrs?.opt("formed"))
        ?: pickFirstString(attrs?.opt("formedOn"))
    val hometown = pickFirstString(attrs?.opt("origin"))
        ?: pickFirstString(attrs?.opt("homeTown"))
        ?: pickFirstString(attrs?.opt("hometown"))
        ?: pickFirstString(attrs?.opt("birthPlace"))
        ?: pickFirstString(attrs?.opt("location"))

    return ShazamArtistDetail(
        id = pickFirstString(dataObject.opt("id")),
        href = pickFirstString(dataObject.opt("href")),
        name = pickFirstString(attrs?.opt("name")),
        imageUrl = amArtworkUrl(attrs?.optJSONObject("artwork")) ?: amArtworkUrl(attrs?.optJSONObject("editorialArtwork")),
        genres = genres,
        description = description,
        hometown = hometown,
        born = born,
        formed = formed
    )
}

private fun parseShazamArtistHtml(html: String): ShazamArtistDetail {
    if (html.isBlank()) return ShazamArtistDetail()

    return try {
        val doc = Jsoup.parse(html)
        var name: String? = null
        var genres: List<String>? = null
        var description: String? = null
        var imageUrl: String? = null

        doc.select("script[type=application/ld+json]").forEach { script ->
            if (name != null) return@forEach
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) return@forEach
            val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return@forEach
            val candidates = when (parsed) {
                is JSONObject -> listOf(parsed)
                is JSONArray -> (0 until parsed.length()).mapNotNull(parsed::optJSONObject)
                else -> emptyList()
            }
            candidates.firstOrNull { candidate ->
                val type = cleanUrl(candidate.optString("@type"))
                type == "MusicGroup" || type == "Person" || type == "MusicArtist"
            }?.let { candidate ->
                name = pickFirstString(candidate.opt("name"))
                genres = when (val rawGenres = candidate.opt("genre")) {
                    is JSONArray -> rawGenres.toStringList()
                    is String -> listOf(rawGenres.trim()).filter { it.isNotBlank() }
                    else -> null
                }
                description = pickFirstString(candidate.opt("description"))
                imageUrl = when (val image = candidate.opt("image")) {
                    is JSONObject -> pickFirstString(image.opt("url"))
                    else -> pickFirstString(image)
                }
            }
        }

        val videoBlock = doc.selectFirst("[data-test-id=artist_impression_artistVideo]")
        val videoElement = videoBlock?.selectFirst("video") ?: doc.selectFirst("video")
        val videoPosterUrl = cleanUrl(videoElement?.attr("poster"))
        val videoSearchHtml = videoBlock?.outerHtml().orEmpty().ifBlank { html }
        val videoHlsUrl = extractFirstM3u8Url(videoSearchHtml) ?: extractFirstM3u8Url(html)

        val members = parseArtistMembers(doc, "[data-test-id*=artist_userevent_artistBandMembers]")
        val memberOf = parseArtistMembers(doc, "[data-test-id*=artist_userevent_memberOfArtistItem]")
        val links = parseArtistLinks(doc)
        val infoMap = parseArtistInfoLines(doc)

        ShazamArtistDetail(
            name = name,
            imageUrl = imageUrl,
            videoPosterUrl = videoPosterUrl,
            videoHlsUrl = videoHlsUrl,
            genres = genres,
            description = description,
            hometown = infoMap["hometown"],
            born = infoMap["born"],
            formed = infoMap["formed"],
            links = links,
            members = members,
            memberOf = memberOf
        )
    } catch (e: Exception) {
        Timber.w(e, "Error parsing Shazam artist HTML")
        ShazamArtistDetail()
    }
}

private fun parseArtistMembers(doc: org.jsoup.nodes.Document, selector: String): List<ArtistMember>? {
    val seen = linkedSetOf<String>()
    val items = doc.select("a$selector").mapNotNull { link ->
        val name = link.text().trim()
        val href = link.attr("href").trim()
        if (name.isBlank() || href.isBlank()) return@mapNotNull null
        val dedupeKey = "${name.lowercase(Locale.US)}|$href"
        if (!seen.add(dedupeKey)) return@mapNotNull null
        ArtistMember(
            name = name,
            href = href,
            image_url = null
        )
    }
    return items.ifEmpty { null }
}

private fun parseArtistLinks(doc: org.jsoup.nodes.Document): List<ArtistLink>? {
    val seen = linkedSetOf<String>()
    val items = doc.select("a[href]").mapNotNull { link ->
        val testId = link.attr("data-test-id").trim()
        if (!testId.contains("artistsociallink", ignoreCase = true)) return@mapNotNull null

        val href = cleanUrl(link.attr("href"))
        if (href.isBlank() || !seen.add(href)) return@mapNotNull null

        ArtistLink(
            href = href,
            platform = detectLinkPlatform(href)
        )
    }
    return items.take(25).ifEmpty { null }
}

private suspend fun enrichMembersWithImages(members: List<ArtistMember>): List<ArtistMember>? {
    if (members.isEmpty()) return null

    return members.take(12).map { member ->
        if (!member.image_url.isNullOrBlank()) {
            member.copy(image_url = resizeAppleImageUrl(member.image_url, size = 618))
        } else {
            val pageUrl = member.href.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: "$SHAZAM_BASE_URL${member.href}"
            val imageUrl = runCatching {
                parseShazamArtistHtml(fetchShazamHtml(pageUrl).orEmpty()).imageUrl
            }.getOrNull()
            if (!imageUrl.isNullOrBlank()) {
                member.copy(image_url = resizeAppleImageUrl(imageUrl, size = 618))
            } else {
                member
            }
        }
    }
}

private fun parseArtistInfoLines(doc: org.jsoup.nodes.Document): Map<String, String> {
    val lines = linkedSetOf<String>()
    doc.select("body *").forEach { element ->
        val ownText = element.ownText().trim()
        if (ownText.isNotBlank()) {
            lines += ownText
        }
    }
    if (lines.isEmpty()) {
        doc.wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { lines += it }
    }

    val out = linkedMapOf<String, String>()
    val lineList = lines.toList()
    val inlineInfoPattern = Regex("""^(hometown|born|formed)\s*:?\s+(.+)$""", RegexOption.IGNORE_CASE)
    lineList.forEachIndexed { index, line ->
        val inlineMatch = inlineInfoPattern.find(line)
        if (inlineMatch != null) {
            val key = inlineMatch.groupValues[1].lowercase(Locale.US)
            val value = inlineMatch.groupValues[2].trim()
            if (value.isNotBlank()) {
                out.putIfAbsent(key, value)
            }
            return@forEachIndexed
        }

        when (line.lowercase(Locale.US)) {
            "hometown" -> lineList.getOrNull(index + 1)?.let { out.putIfAbsent("hometown", it) }
            "born" -> lineList.getOrNull(index + 1)?.let { out.putIfAbsent("born", it) }
            "formed" -> lineList.getOrNull(index + 1)?.let { out.putIfAbsent("formed", it) }
        }
    }
    return out
}

private fun extractArtistSearchItems(payload: Any?): List<JSONObject> {
    return when (payload) {
        is JSONObject -> {
            val results = payload.optJSONObject("results")
            val artistData = results?.optJSONObject("artists")?.optJSONArray("data")
            if (artistData != null) {
                (0 until artistData.length()).mapNotNull(artistData::optJSONObject)
            } else {
                val rootData = payload.optJSONArray("data")
                if (rootData != null) {
                    (0 until rootData.length()).mapNotNull(rootData::optJSONObject)
                } else {
                    val keys = payload.keys()
                    while (keys.hasNext()) {
                        val nested = extractArtistSearchItems(payload.opt(keys.next()))
                        if (nested.isNotEmpty()) return nested
                    }
                    emptyList()
                }
            }
        }
        is JSONArray -> {
            for (index in 0 until payload.length()) {
                val nested = extractArtistSearchItems(payload.opt(index))
                if (nested.isNotEmpty()) return nested
            }
            emptyList()
        }
        else -> emptyList()
    }
}

private fun artistQueryScore(query: String, name: String): Double {
    val normalizedQuery = normalizeArtistQuery(query)
    val normalizedName = normalizeArtistQuery(name)
    if (normalizedQuery.isBlank() || normalizedName.isBlank()) return 0.0
    if (normalizedQuery == normalizedName) return 1000.0

    var score = similarityRatio(normalizedQuery, normalizedName) * 100.0
    if (normalizedName.startsWith(normalizedQuery)) score += 50.0
    if (normalizedName.contains(normalizedQuery)) score += 25.0

    val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }.toSet()
    val nameTokens = normalizedName.split(' ').filter { it.isNotBlank() }.toSet()
    if (queryTokens.isNotEmpty() && nameTokens.isNotEmpty()) {
        val intersection = queryTokens.intersect(nameTokens).size.toDouble()
        val union = queryTokens.union(nameTokens).size.toDouble()
        score += (intersection / union) * 40.0
    }
    return score
}

private fun normalizeArtistQuery(value: String): String =
    value.trim()
        .lowercase(Locale.US)
        .replace(Regex("""[^a-z0-9\s]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun similarityRatio(left: String, right: String): Double {
    val maxLength = max(left.length, right.length)
    if (maxLength == 0) return 1.0
    val distance = levenshteinDistance(left, right)
    return 1.0 - distance.toDouble() / maxLength.toDouble()
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length

    val previous = IntArray(right.length + 1) { it }
    val current = IntArray(right.length + 1)

    for (i in left.indices) {
        current[0] = i + 1
        for (j in right.indices) {
            val cost = if (left[i] == right[j]) 0 else 1
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + cost
            )
        }
        current.copyInto(previous)
    }

    return previous[right.length]
}

private fun inferFormed(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return null
    val normalized = text.replace('\u2014', '-').replace('\u2013', '-')
    return YEAR_FROM_DESCRIPTION_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)
    }
}

private fun pickFirstString(value: Any?): String? = when (value) {
    is String -> cleanUrl(value).takeIf { it.isNotBlank() }
    is JSONObject -> listOf("name", "value", "text", "title", "label", "displayName", "display_name")
        .firstNotNullOfOrNull { key -> pickFirstString(value.opt(key)) }
    is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { index -> pickFirstString(value.opt(index)) }
    else -> null
}

private fun cleanUrl(value: Any?): String =
    (value as? String)
        ?.trim()
        ?.removePrefix("`")
        ?.removeSuffix("`")
        ?.trim()
        .orEmpty()

private fun resizeAppleImageUrl(url: String?, size: Int = 618): String? {
    val source = cleanUrl(url)
    if (source.isBlank()) return null
    return APPLE_IMAGE_SIZE_REGEX.replace(source) { matchResult ->
        val suffix = matchResult.groupValues[3]
        val extension = matchResult.groupValues[4]
        "/${size}x${size}${suffix}.${extension}"
    }.ifBlank { source }
}

private fun itunesMp4FromM3u8(url: String?): String? {
    val source = cleanUrl(url)
    if (source.isBlank()) return null
    val match = ITUNES_M3U8_REGEX.find(source) ?: return null
    val base = match.groupValues[1]
    val pid = match.groupValues[2]
    return "${base}${pid}_Anull_video_gr697_sdr_3840x2160-.mp4"
}

private fun amArtworkUrl(artwork: JSONObject?, size: Int = 1000): String? {
    val raw = artwork?.optString("url").orEmpty().trim()
    if (raw.isBlank()) return null
    return raw.replace("{w}", size.toString()).replace("{h}", size.toString())
}

private fun extractFirstM3u8Url(source: String): String? {
    val match = M3U8_URL_REGEX.find(source) ?: return null
    return cleanUrl(match.value)
}

private fun shazamArtistSlug(name: String): String =
    slugify(name).replace("_", "-")

private fun slugify(value: String): String {
    val normalized = Normalizer.normalize(value.trim().lowercase(Locale.US), Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
    return normalized
        .replace(Regex("""[^a-z0-9]+"""), "_")
        .replace(Regex("""_+"""), "_")
        .trim('_')
}

private fun detectLinkPlatform(href: String): String {
    return runCatching {
        val host = URL(
            when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "$SHAZAM_BASE_URL$href"
                else -> href
            }
        ).host.removePrefix("www.").lowercase(Locale.US)

        when {
            "instagram" in host -> "instagram"
            host == "x.com" || "twitter" in host -> "x"
            "facebook" in host -> "facebook"
            "youtube" in host || host == "youtu.be" -> "youtube"
            "tiktok" in host -> "tiktok"
            "soundcloud" in host -> "soundcloud"
            "spotify" in host -> "spotify"
            host.isNotBlank() -> host
            else -> ""
        }
    }.getOrDefault("")
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length())
        .mapNotNull { index -> pickFirstString(opt(index)) }
        .filter { it.isNotBlank() }

package com.cncverse

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.nicehttp.NiceResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.JsonNode

class MovieBoxProviderIN : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "https://api6.aoneroom.com"
    override var name = "MovieBox IN"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val secretKeyDefault = System.getenv("MOVIEBOX_SECRET_KEY_DEFAULT")
        ?.takeIf { it.isNotBlank() }
        ?: "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"

    private val secretKeyAlt = System.getenv("MOVIEBOX_SECRET_KEY_ALT")
        ?.takeIf { it.isNotBlank() }
        ?: "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"

    private val authToken = System.getenv("MOVIEBOX_AUTH_TOKEN")?.takeIf { it.isNotBlank() }

    private val hostPool = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    private val userAgentHeader = "com.community.oneroom/50020052 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)"
    private val clientInfoHeader = """{"package_name":"com.community.oneroom","version_name":"3.0.05.0711.03","version_code":50020052,"os":"android","os_version":"16","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
    private val debugLoadLinks = true
    private val debugThrowInUi = false

    private fun shouldRetryHost(code: Int): Boolean {
        return code == 403 || code == 407 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
    }

    private fun buildSignedHeaders(
        method: String,
        url: String,
        accept: String,
        contentType: String,
        body: String? = null,
        includePlayMode: Boolean = false
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val headers = mutableMapOf(
            "User-Agent" to userAgentHeader,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "X-Client-Token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(method, accept, contentType, url, body, false, timestamp),
            "X-Client-Info" to clientInfoHeader,
            "X-Client-Status" to "0"
        )
        authToken?.let { headers["Authorization"] = "Bearer $it" }
        if (includePlayMode) {
            headers["X-Play-Mode"] = "2"
        }
        return headers
    }

    private suspend fun signedGetWithFallback(
        pathAndQuery: String,
        accept: String = "application/json",
        contentType: String = "application/json",
        includePlayMode: Boolean = false
    ): Pair<String, NiceResponse> {
        var last: Pair<String, NiceResponse>? = null
        for (base in hostPool) {
            val url = "$base$pathAndQuery"
            val response = app.get(url, headers = buildSignedHeaders("GET", url, accept, contentType, null, includePlayMode))
            last = Pair(base, response)
            if (!shouldRetryHost(response.code)) {
                mainUrl = base
                return last
            }
        }
        return last ?: Pair(mainUrl, app.get("$mainUrl$pathAndQuery", headers = buildSignedHeaders("GET", "$mainUrl$pathAndQuery", accept, contentType, null, includePlayMode)))
    }

    private suspend fun signedPostWithFallback(
        pathAndQuery: String,
        body: String,
        accept: String = "application/json",
        contentType: String = "application/json; charset=utf-8"
    ): Pair<String, NiceResponse> {
        var last: Pair<String, NiceResponse>? = null
        val requestBody = body.toRequestBody("application/json".toMediaType())
        for (base in hostPool) {
            val url = "$base$pathAndQuery"
            val response = app.post(url, headers = buildSignedHeaders("POST", url, accept, contentType, body), requestBody = requestBody)
            last = Pair(base, response)
            if (!shouldRetryHost(response.code)) {
                mainUrl = base
                return last
            }
        }
        return last ?: Pair(mainUrl, app.post("$mainUrl$pathAndQuery", headers = buildSignedHeaders("POST", "$mainUrl$pathAndQuery", accept, contentType, body), requestBody = requestBody))
    }


    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        
        // Build query string with sorted parameters (if any)
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"  // Don't URL encode here - Python doesn't do it
                }
            }
        } else ""
        
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "${timestamp.toString()}\n" +
                "$bodyHash\n" +
                "$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val (_, response) = signedGetWithFallback(
            pathAndQuery = "/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=",
            includePlayMode = true
        )
        val responseBody = response.text

        // Helper function to parse a 'subject' JSON object into your app's data model.
        fun parseSubject(subjectJson: JsonNode?): SearchResponse? {
            subjectJson ?: return null // Return null if the subject object is missing
            val subjectId = subjectJson["subjectId"]?.asText() ?: return null
            val title = subjectJson["title"]?.asText() ?: return null
            val coverUrl = subjectJson["cover"]?.get("url")?.asText()
            val subjectType = when (subjectJson["subjectType"]?.asInt()) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie // Default to Movie
            }
            return newMovieSearchResponse(title, subjectId, subjectType) {
                this.posterUrl = coverUrl
            }
        }

        // Use Jackson to parse the new, multi-section API response structure.
        val homePageLists = try {
            val mapper = jacksonObjectMapper()
            val root = mapper.readTree(responseBody)
            val sections = root["data"]?.get("items") ?: return newHomePageResponse(emptyList())

            // Iterate through each section (e.g., Banners, Trending Now, etc.)
            sections.mapNotNull { section ->
                val title = section["title"]?.asText()?.let {
                    if (it.equals("banner", ignoreCase = true)) "🔥Top Picks" else it
                } ?: return@mapNotNull null
                val type = section["type"]?.asText()

                // Extract the list of media items based on the section type.
                val mediaList = when (type) {
                    "BANNER" -> section["banner"]?.get("banners")
                        ?.mapNotNull { bannerItem -> parseSubject(bannerItem["subject"]) }
                    "SUBJECTS_MOVIE" -> section["subjects"]
                        ?.mapNotNull { subjectItem -> parseSubject(subjectItem) }
                    "CUSTOM" -> section["customData"]?.get("items")
                        ?.mapNotNull { customItem -> parseSubject(customItem["subject"]) }
                    else -> null
                }

                // Only create a HomePageList if the section contains valid media items.
                if (mediaList.isNullOrEmpty()) {
                    null
                } else {
                    HomePageList(title, mediaList)
                }
            }
        } catch (e: Exception) {
            // In case of a parsing error, return an empty list.
            e.printStackTrace()
            emptyList()
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val path = "/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$query"}"""
        val (_, response) = signedPostWithFallback(pathAndQuery = path, body = jsonBody)
        val responseBody = response.text
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val results = root["data"]?.get("results") ?: return emptyList()
        val searchList = mutableListOf<SearchResponse>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
            val title = subject["title"]?.asText() ?: continue
            val id = subject["subjectId"]?.asText() ?: continue
            val coverImg = subject["cover"]?.get("url")?.asText()
            val subjectType = subject["subjectType"]?.asInt() ?: 1
            val type = when (subjectType) {
                        1 -> TvType.Movie
                        2 -> TvType.TvSeries
                        else -> TvType.Movie
                }
            searchList.add(
                newMovieSearchResponse(
                name = title,
                url = id,
                type = type
                ) {
                posterUrl = coverImg
                }
            )
            }
        }
        return searchList
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = if (url.contains("get?subjectId")) {
            Uri.parse(url).getQueryParameter("subjectId") ?: url.substringAfterLast('/')
        } else {
            url.substringAfterLast('/')
        }
        val (activeBaseUrl, response) = signedGetWithFallback(
            pathAndQuery = "/wefeed-mobile-bff/subject-api/get?subjectId=$id",
            includePlayMode = true
        )
        val finalUrl = "$activeBaseUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.text}")
        }
        val responseBody = response.text.ifBlank { throw ErrorLoadingException("Empty response body") }
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val data = root["data"] ?: throw ErrorLoadingException("No data in response")

        val title = data["title"]?.asText() ?: throw ErrorLoadingException("No title found")
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val duration = data["duration"]?.asText()
        val genre = data["genre"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.substring(0, 4)?.toIntOrNull()
        val coverUrl = data["cover"]?.get("url")?.asText()
        val backgroundUrl = data["cover"]?.get("url")?.asText()
        val subjectType = data["subjectType"]?.asInt() ?: 1
        val countryName = data["countryName"]?.asText()

        // Parse cast information
        val actors = data["staffList"]?.mapNotNull { staff ->
            val staffType = staff["staffType"]?.asInt()
            if (staffType == 1) { // Actor
                val name = staff["name"]?.asText() ?: return@mapNotNull null
                val character = staff["character"]?.asText()
                val avatarUrl = staff["avatarUrl"]?.asText()
                ActorData(
                    Actor(name, avatarUrl),
                    roleString = character
                )
            } else null
        } ?: emptyList()

        // Parse tags/genres
        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        // Parse duration to minutes
        val durationMinutes = duration?.let { dur ->
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val match = regex.find(dur)
            if (match != null) {
                val hours = match.groupValues[1].toIntOrNull() ?: 0
                val minutes = match.groupValues[2].toIntOrNull() ?: 0
                hours * 60 + minutes
            } else {
                dur.replace("m", "").toIntOrNull()
            }
        }

        val type = when (subjectType) {
            1 -> TvType.Movie
            2 -> TvType.TvSeries
            else -> TvType.Movie
        }

        if (type == TvType.TvSeries) {
            // For TV series, get season and episode information
            val (_, seasonResponse) = signedGetWithFallback("/wefeed-mobile-bff/subject-api/season-info?subjectId=$id")
            val episodes = mutableListOf<Episode>()
            
            if (seasonResponse.code == 200) {
                val seasonResponseBody = seasonResponse.text
                if (seasonResponseBody != null) {
                    val seasonRoot = mapper.readTree(seasonResponseBody)
                    val seasonData = seasonRoot["data"]
                    val seasons = seasonData?.get("seasons")
                    
                    seasons?.forEach { season ->
                        val seasonNumber = season["se"]?.asInt() ?: 1
                        val maxEpisodes = season["maxEp"]?.asInt() ?: 1
                        for (episodeNumber in 1..maxEpisodes) {
                            episodes.add(
                                newEpisode("$id|$seasonNumber|$episodeNumber") {
                                    this.name = "S${seasonNumber}E${episodeNumber}"
                                    this.season = seasonNumber
                                    this.episode = episodeNumber
                                    this.posterUrl = coverUrl
                                    this.description = "Season $seasonNumber Episode $episodeNumber"
                                }
                            )
                        }
                    }
                }
            }
            
            // If no episodes were found, add a fallback episode
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$id|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }
            
            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = imdbRating?.let { Score.from10(it.toDouble()) }
                this.duration = durationMinutes
            }
        } else {
            return newMovieLoadResponse(title, finalUrl, type, id) {
                this.posterUrl = coverUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = imdbRating?.let { Score.from10(it.toDouble()) }
                this.duration = durationMinutes
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val parts = data.split("|")
            val originalSubjectId = if (parts[0].contains("get?subjectId")) {
                Uri.parse(parts[0]).getQueryParameter("subjectId") ?: parts[0].substringAfterLast('/')
            } else if(parts[0].contains("/")) {
                parts[0].substringAfterLast('/')
            }
            else {
                parts[0]
            }
            val parsedSeason = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val parsedEpisode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
            val season = if (parsedSeason > 0) parsedSeason else 1
            val episode = if (parsedEpisode > 0) parsedEpisode else 1

            if (debugLoadLinks) {
                debugPlayInfoProbe(originalSubjectId, season, episode)
            }

            val (_, subjectResponse) = signedGetWithFallback("/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId")
            val mapper = jacksonObjectMapper()
            val subjectIds = mutableListOf<Pair<String, String>>() // Pair of (subjectId, language)
            var originalLanguageName = "Original"
            val visitedUrls = mutableSetOf<String>()

            suspend fun emitLink(
                sourceName: String,
                streamUrl: String,
                language: String,
                resolution: String,
                refererBase: String,
                signCookie: String? = null
            ) {
                if (streamUrl.isBlank() || !visitedUrls.add(streamUrl)) return
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (resolution.isNotBlank()) "$name ($language - $resolution)" else "$name ($language)",
                        url = streamUrl,
                        type = when {
                            streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                            streamUrl.substringAfterLast('.', "").equals("mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                            streamUrl.substringAfterLast('.', "").equals("torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                            sourceName.equals("HLS", ignoreCase = true) || streamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.headers = mapOf("Referer" to refererBase)
                        this.quality = Qualities.Unknown.value
                        if (!signCookie.isNullOrBlank()) {
                            this.headers = this.headers + mapOf("Cookie" to signCookie)
                        }
                    }
                )
            }

            suspend fun extractFallbackLinksFromSubject(
                subjectData: JsonNode?,
                language: String,
                refererBase: String
            ): Boolean {
                var emitted = false
                val detectors = subjectData?.get("resourceDetectors")
                if (detectors != null && detectors.isArray) {
                    for (detector in detectors) {
                        val detectorLink = detector["resourceLink"]?.asText()?.takeIf { it.isNotBlank() }
                            ?: detector["downloadUrl"]?.asText()?.takeIf { it.isNotBlank() }
                            ?: detector["sourceUrl"]?.asText()?.takeIf { it.isNotBlank() }
                        if (!detectorLink.isNullOrBlank()) {
                            emitLink(
                                sourceName = detector["format"]?.asText() ?: "",
                                streamUrl = detectorLink,
                                language = language,
                                resolution = detector["resolution"]?.asText() ?: detector["title"]?.asText() ?: "Resource",
                                refererBase = refererBase
                            )
                            emitted = true
                        }

                        val resolutionList = detector["resolutionList"]
                        if (resolutionList != null && resolutionList.isArray) {
                            for (item in resolutionList) {
                                val link = item["downloadUrl"]?.asText()?.takeIf { it.isNotBlank() }
                                    ?: item["resourceLink"]?.asText()?.takeIf { it.isNotBlank() }
                                    ?: item["sourceUrl"]?.asText()?.takeIf { it.isNotBlank() }
                                if (!link.isNullOrBlank()) {
                                    emitLink(
                                        sourceName = item["format"]?.asText() ?: "",
                                        streamUrl = link,
                                        language = language,
                                        resolution = item["resolutions"]?.asText()
                                            ?: item["resolution"]?.asText()
                                            ?: item["title"]?.asText()
                                            ?: "Resource",
                                        refererBase = refererBase
                                    )
                                    emitted = true
                                }

                                val captions = item["extCaptions"]
                                if (captions != null && captions.isArray) {
                                    for (caption in captions) {
                                        val captionUrl = caption["url"]?.asText() ?: continue
                                        val lang = caption["language"]?.asText()
                                            ?: caption["lanName"]?.asText()
                                            ?: caption["lan"]?.asText()
                                            ?: "Unknown"
                                        subtitleCallback.invoke(
                                            SubtitleFile(
                                                url = captionUrl,
                                                lang = "$lang ($language)"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                return emitted
            }

            if (subjectResponse.code == 200) {
                val subjectResponseBody = subjectResponse.text
                if (subjectResponseBody.isNotBlank()) {
                    val subjectRoot = mapper.readTree(subjectResponseBody)
                    val subjectData = subjectRoot["data"]
                    val dubs = subjectData?.get("dubs")
                    if (dubs != null && dubs.isArray) {
                        for (dub in dubs) {
                            val dubSubjectId = dub["subjectId"]?.asText()
                            val lanName = dub["lanName"]?.asText()
                            if (dubSubjectId != null && lanName != null) {
                                if (dubSubjectId == originalSubjectId) {
                                    originalLanguageName = lanName
                                } else {
                                    subjectIds.add(Pair(dubSubjectId, lanName))
                                }
                            }
                        }
                    }
                }
            }
            
            // Always add the original subject ID first as the default source with proper language name
            subjectIds.add(0, Pair(originalSubjectId, originalLanguageName))
            
            var hasAnyLinks = false
            
            // Process each subjectId (including dubs)
            for ((subjectId, language) in subjectIds) {
                var emittedFromPlayInfo = false
                var activeBaseUrl = mainUrl
                try {
                    val (resolvedBaseUrl, response) = signedGetWithFallback(
                        "/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode",
                        includePlayMode = true
                    )
                    activeBaseUrl = resolvedBaseUrl
                    if (response.code == 200) {
                        val responseBody = response.text
                        if (responseBody.isNotBlank()) {
                            val root = mapper.readTree(responseBody)
                            val playData = root["data"]
                            // Handle the new API response format with streams
                            val streams = playData?.get("streams")
                            if (streams != null && streams.isArray) {
                                for (stream in streams) {
                                    val streamUrl = stream["url"]?.asText() ?: continue
                                    val format = stream["format"]?.asText() ?: ""
                                    val resolutions = stream["resolutions"]?.asText() ?: ""
                                    val signCookieRaw = stream["signCookie"]?.asText()
                                    val signCookie = if (signCookieRaw.isNullOrEmpty()) null else signCookieRaw
                                    val id = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                                    emitLink(
                                        sourceName = format,
                                        streamUrl = streamUrl,
                                        language = language,
                                        resolution = resolutions,
                                        refererBase = activeBaseUrl,
                                        signCookie = signCookie
                                    )
                                    hasAnyLinks = true
                                    emittedFromPlayInfo = true

                                    val (_, subResponse) = signedGetWithFallback(
                                        "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$id"
                                    )
                                    if (subResponse.code == 200) {
                                        val subResponseBody = subResponse.text
                                        if (!subResponseBody.isNullOrBlank()) {
                                            val subRoot = mapper.readTree(subResponseBody)
                                            val extCaptions = subRoot["data"]?.get("extCaptions")
                                            if (extCaptions != null && extCaptions.isArray) {
                                                for (caption in extCaptions) {
                                                    val captionUrl = caption["url"]?.asText() ?: continue
                                                    val lang = caption["language"]?.asText()
                                                        ?: caption["lanName"]?.asText()
                                                        ?: caption["lan"]?.asText()
                                                        ?: "Unknown"
                                                    subtitleCallback.invoke(
                                                        SubtitleFile(
                                                            url = captionUrl,
                                                            lang = "$lang ($language - $resolutions)"
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                        

                                    val (_, subResponse1) = signedGetWithFallback(
                                        "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$id&episode=$episode"
                                    )
                            
                                        if (subResponse1.code == 200) {
                                            val subResponseBody1 = subResponse1.text
                                            if (!subResponseBody1.isNullOrBlank()) {
                                            val subRoot = mapper.readTree(subResponseBody1)
                                            val extCaptions = subRoot["data"]?.get("extCaptions")
                                            if (extCaptions != null && extCaptions.isArray) {
                                                for (caption in extCaptions) {
                                                    val captionUrl = caption["url"]?.asText() ?: continue
                                                    val lang = caption["lan"]?.asText()
                                                        ?: caption["lanName"]?.asText()
                                                        ?: caption["language"]?.asText()
                                                        ?: "Unknown"
                                                    subtitleCallback.invoke(
                                                        SubtitleFile(
                                                            url = captionUrl,
                                                            lang = "$lang ($language - $resolutions)"
                                                        )
                                                    )
                                                }
                                            }
                                            }
                                        }
                                    
                                }
                            }

                                if (!emittedFromPlayInfo) {
                                    val fallbackFromSubject = extractFallbackLinksFromSubject(
                                        subjectData = playData,
                                        language = language,
                                        refererBase = activeBaseUrl
                                    )
                                    if (fallbackFromSubject) {
                                        hasAnyLinks = true
                                    }
                                }
                        }
                    }

                } catch (e: Exception) {
                    // Continue to resource fallback using the last known active base.
                }

                if (!emittedFromPlayInfo) {
                    val (_, resourceResponse) = signedGetWithFallback(
                        "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&page=1&perPage=8&all=0&startPosition=1&endPosition=1&pagerMode=0&resolution=0&se=$season&epFrom=$episode&epTo=$episode",
                        includePlayMode = true
                    )
                    if (resourceResponse.code == 200) {
                        val resourceBody = resourceResponse.text
                        if (!resourceBody.isNullOrBlank()) {
                            val resourceRoot = mapper.readTree(resourceBody)
                            val resourceData = resourceRoot["data"]
                            val fallbackFromResource = extractFallbackLinksFromSubject(
                                subjectData = resourceData,
                                language = language,
                                refererBase = activeBaseUrl
                            )
                            if (fallbackFromResource) {
                                hasAnyLinks = true
                            }
                        }
                    }
                }
            }
            
            return hasAnyLinks
              
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun debugPlayInfoProbe(subjectId: String, season: Int, episode: Int) {
        val mapper = jacksonObjectMapper()
        val playPath = "/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"

        Log.d("MBX", "=== LOAD LINKS START ===")
        Log.d("MBX", "SubjectId: $subjectId")
        Log.d("MBX", "Season: $season Episode: $episode")
        Log.d("MBX", "MainUrl: $mainUrl")
        Log.d("MBX", "Play path: $playPath")

        for (host in hostPool) {
            val fullUrl = "$host$playPath"
            val ts = System.currentTimeMillis()
            val headers = mapOf(
                "User-Agent" to userAgentHeader,
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Connection" to "keep-alive",
                "X-Client-Token" to generateXClientToken(ts),
                "x-tr-signature" to generateXTrSignature("GET", "application/json", "application/json", fullUrl, null, false, ts),
                "X-Client-Info" to clientInfoHeader,
                "X-Client-Status" to "0",
                "X-Play-Mode" to "2"
            )

            try {
                val resp = app.get(fullUrl, headers = headers)
                val body = resp.text
                Log.d("MBX", "Host: $host")
                Log.d("MBX", "Code: ${resp.code}")
                Log.d("MBX", "Body: ${body.take(500)}")

                if (resp.code == 200) {
                    val root = mapper.readTree(body)
                    val apiCode = root["code"]?.asInt()
                    val message = root["message"]?.asText()
                    val playData = root["data"]

                    Log.d("MBX", "API Code: $apiCode")
                    Log.d("MBX", "API Message: $message")
                    if (playData != null && !playData.isNull) {
                        val keys = playData.fieldNames().asSequence().toList()
                        Log.d("MBX", "Data keys: $keys")
                        val streams = playData["streams"]
                        Log.d("MBX", "Streams: ${if (streams != null && streams.isArray) streams.size() else "NULL"}")
                        if (streams != null && streams.isArray) {
                            for (i in 0 until streams.size()) {
                                val s = streams[i]
                                Log.d("MBX", "Stream[$i] url: ${s["url"]?.asText()?.take(100)}")
                                Log.d("MBX", "Stream[$i] format: ${s["format"]?.asText()}")
                                Log.d("MBX", "Stream[$i] res: ${s["resolutions"]?.asText()}")
                            }
                        }
                        val detectors = playData["resourceDetectors"]
                        Log.d("MBX", "ResourceDetectors: ${if (detectors != null && detectors.isArray) detectors.size() else "NULL"}")
                    } else {
                        Log.d("MBX", "Data is NULL")
                    }

                    if (debugThrowInUi) {
                        throw ErrorLoadingException("DEBUG - Code: ${resp.code} | Body: ${body.take(300)}")
                    }
                    break
                }
            } catch (e: Exception) {
                Log.d("MBX", "Host $host error: ${e.message}")
            }
        }

        Log.d("MBX", "=== LOAD LINKS END ===")
    }
}

data class MovieBoxMainResponse(
    val code: Int? = null,
    val message: String? = null,
    val data: MovieBoxData? = null
)

data class MovieBoxData(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    val cover: MovieBoxCover? = null,
    val countryName: String? = null,
    val language: String? = null,
    val imdbRatingValue: String? = null,
    val staffList: List<MovieBoxStaff>? = null,
    val hasResource: Boolean? = null,
    val resourceDetectors: List<MovieBoxResourceDetector>? = null,
    val year: Int? = null,
    val durationSeconds: Int? = null,
    val dubs: List<MovieBoxDub>? = null
)

data class MovieBoxCover(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Int? = null,
    val format: String? = null
)

data class MovieBoxStaff(
    val staffId: String? = null,
    val staffType: Int? = null,
    val name: String? = null,
    val character: String? = null,
    val avatarUrl: String? = null
)

data class MovieBoxResourceDetector(
    val type: Int? = null,
    val totalEpisode: Int? = null,
    val totalSize: String? = null,
    val uploadTime: String? = null,
    val uploadBy: String? = null,
    val resourceLink: String? = null,
    val downloadUrl: String? = null,
    val source: String? = null,
    val firstSize: String? = null,
    val resourceId: String? = null,
    val postId: String? = null,
    val extCaptions: List<MovieBoxCaption>? = null,
    val resolutionList: List<MovieBoxResolution>? = null,
    val subjectId: String? = null,
    val codecName: String? = null
)

data class MovieBoxResolution(
    val episode: Int? = null,
    val title: String? = null,
    val resourceLink: String? = null,
    val linkType: Int? = null,
    val size: String? = null,
    val uploadBy: String? = null,
    val resourceId: String? = null,
    val postId: String? = null,
    val extCaptions: List<MovieBoxCaption>? = null,
    val se: Int? = null,
    val ep: Int? = null,
    val sourceUrl: String? = null,
    val resolution: Int? = null,
    val codecName: String? = null,
    val duration: Int? = null,
    val requireMemberType: Int? = null,
    val memberIcon: String? = null
)

data class MovieBoxCaption(
    val url: String? = null,
    val label: String? = null,
    val language: String? = null
)

data class MovieBoxSeasonResponse(
    val code: Int? = null,
    val message: String? = null,
    val data: MovieBoxSeasonData? = null
)

data class MovieBoxSeasonData(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val seasons: List<MovieBoxSeason>? = null
)

data class MovieBoxSeason(
    val se: Int? = null,
    val maxEp: Int? = null,
    val allEp: String? = null,
    val resolutions: List<MovieBoxSeasonResolution>? = null
)

data class MovieBoxSeasonResolution(
    val resolution: Int? = null,
    val epNum: Int? = null
)

data class MovieBoxStreamResponse(
    val code: Int? = null,
    val message: String? = null,
    val data: MovieBoxStreamData? = null
)

data class MovieBoxStreamData(
    val streams: List<MovieBoxStream>? = null,
    val title: String? = null
)

data class MovieBoxStream(
    val format: String? = null,
    val id: String? = null,
    val url: String? = null,
    val resolutions: String? = null,
    val size: String? = null,
    val duration: Int? = null,
    val codecName: String? = null,
    val signCookie: String? = null
)

data class MovieBoxDub(
    val subjectId: String? = null,
    val lanName: String? = null,
    val lanCode: String? = null,
    val original: Boolean? = null,
    val type: Int? = null
)

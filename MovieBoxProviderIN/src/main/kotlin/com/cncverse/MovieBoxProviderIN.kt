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
    private val debugLoadLinks = false
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
        val id = when {
            url.contains("get?subjectId") -> Uri.parse(url).getQueryParameter("subjectId") ?: url
            url.contains("/") -> url.substringAfterLast('/')
            else -> url
        }

        val path = "/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val fullUrl = "$mainUrl$path"
        val ts = System.currentTimeMillis()

        val headers = mapOf(
            "User-Agent" to userAgentHeader,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "X-Client-Token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature(
                "GET", "application/json", "application/json",
                fullUrl, null, false, ts
            ),
            "X-Client-Info" to clientInfoHeader,
            "X-Client-Status" to "0"
        )

        val response = app.get(fullUrl, headers = headers)
        val responseBody = response.text
        if (responseBody.isBlank()) throw ErrorLoadingException("Empty response")

        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(responseBody)
        val data = root["data"] ?: throw ErrorLoadingException("No data")

        val title = data["title"]?.asText() ?: "Unknown"
        val description = data["description"]?.asText()
        val coverUrl = data["cover"]?.get("url")?.asText()
        val subjectType = data["subjectType"]?.asInt() ?: 1
        val releaseDate = data["releaseDate"]?.asText()
        val year = try { releaseDate?.substring(0, 4)?.toIntOrNull() } catch (_: Exception) { null }
        val genre = data["genre"]?.asText()
        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        val type = if (subjectType == 2) TvType.TvSeries else TvType.Movie

        if (type == TvType.TvSeries) {
            val seasonPath = "/wefeed-mobile-bff/subject-api/season-info?subjectId=$id"
            val seasonUrl = "$mainUrl$seasonPath"
            val ts2 = System.currentTimeMillis()
            val seasonHeaders = mapOf(
                "User-Agent" to userAgentHeader,
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Connection" to "keep-alive",
                "X-Client-Token" to generateXClientToken(ts2),
                "x-tr-signature" to generateXTrSignature(
                    "GET", "application/json", "application/json",
                    seasonUrl, null, false, ts2
                ),
                "X-Client-Info" to clientInfoHeader,
                "X-Client-Status" to "0"
            )

            val episodes = mutableListOf<Episode>()
            try {
                val seasonResp = app.get(seasonUrl, headers = seasonHeaders)
                if (seasonResp.code == 200) {
                    val seasonBody = seasonResp.text
                    if (seasonBody.isNotBlank()) {
                        val seasonRoot = mapper.readTree(seasonBody)
                        val seasons = seasonRoot["data"]?.get("seasons")
                        seasons?.forEach { season ->
                            val se = season["se"]?.asInt() ?: 1
                            val maxEp = season["maxEp"]?.asInt() ?: 1
                            for (ep in 1..maxEp) {
                                episodes.add(
                                    newEpisode("$id|$se|$ep") {
                                        this.name = "S${se}E${ep}"
                                        this.season = se
                                        this.episode = ep
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            if (episodes.isEmpty()) {
                episodes.add(newEpisode("$id|1|1") {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                })
            }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = coverUrl
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, type, id) {
                this.posterUrl = coverUrl
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val subjectId = when {
            parts[0].contains("get?subjectId") -> Uri.parse(parts[0]).getQueryParameter("subjectId") ?: parts[0]
            parts[0].contains("/") -> parts[0].substringAfterLast('/')
            else -> parts[0]
        }
        val season = (if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0).coerceAtLeast(1)
        val episode = (if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0).coerceAtLeast(1)

        val playPath = "/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
        val fullUrl = "$mainUrl$playPath"
        val ts = System.currentTimeMillis()

        val headers = mapOf(
            "User-Agent" to userAgentHeader,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "X-Client-Token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature(
                "GET", "application/json", "application/json",
                fullUrl, null, false, ts
            ),
            "X-Client-Info" to clientInfoHeader,
            "X-Client-Status" to "0",
            "X-Play-Mode" to "2"
        )

        val resp = app.get(fullUrl, headers = headers)
        val body = resp.text
        val mapper = jacksonObjectMapper()
        val emitted = mutableSetOf<String>()
        var hasLinks = false

        suspend fun emit(url: String, displayRes: String = "Unknown", cookie: String? = null): Boolean {
            if (url.isBlank() || !emitted.add(url)) return false
            if (url.startsWith("magnet:", true) || url.endsWith(".torrent", true)) return false
            val isM3u8 = url.contains(".m3u8", true) || url.contains("m3u8", true)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} ($displayRes)",
                    url = url,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mutableMapOf(
                        "User-Agent" to userAgentHeader,
                        "Referer" to mainUrl,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    ).apply {
                        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                    }
                }
            )
            return true
        }

        if (body.isNotBlank()) {
            val root = mapper.readTree(body)
            val playData = root["data"]
            val streams = playData?.get("streams")
            if (streams != null && streams.isArray) {
                for (stream in streams) {
                    val streamUrl = stream["url"]?.asText() ?: continue
                    val format = stream["format"]?.asText() ?: ""
                    val res = stream["resolutions"]?.asText() ?: "Unknown"
                    val cookie = stream["signCookie"]?.asText()?.takeIf { it.isNotBlank() }
                    val chosenUrl = streamUrl
                    val ok = emit(chosenUrl, if (format.isNotBlank()) "$res/$format" else res, cookie)
                    if (ok) hasLinks = true
                }
            }
        }

        if (!hasLinks) {
            val detailsPath = "/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
            val detailsUrl = "$mainUrl$detailsPath"
            val ts2 = System.currentTimeMillis()
            val headers2 = mapOf(
                "User-Agent" to userAgentHeader,
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Connection" to "keep-alive",
                "X-Client-Token" to generateXClientToken(ts2),
                "x-tr-signature" to generateXTrSignature(
                    "GET", "application/json", "application/json",
                    detailsUrl, null, false, ts2
                ),
                "X-Client-Info" to clientInfoHeader,
                "X-Client-Status" to "0",
                "X-Play-Mode" to "2"
            )

            val detailsResp = app.get(detailsUrl, headers = headers2)
            val detailsBody = detailsResp.text
            if (detailsBody.isNotBlank()) {
                val detailsRoot = mapper.readTree(detailsBody)
                val dataNode = detailsRoot["data"]
                val detectors = dataNode?.get("resourceDetectors")
                if (detectors != null && detectors.isArray) {
                    for (det in detectors) {
                        val direct = det["resourceLink"]?.asText()?.takeIf { it.isNotBlank() }
                            ?: det["downloadUrl"]?.asText()?.takeIf { it.isNotBlank() }
                        if (!direct.isNullOrBlank()) {
                            if (emit(direct, det["resolution"]?.asText() ?: "Resource")) hasLinks = true
                        }

                        val resolutionList = det["resolutionList"]
                        if (resolutionList != null && resolutionList.isArray) {
                            for (item in resolutionList) {
                                val itemSe = item["se"]?.asInt()
                                val itemEp = item["ep"]?.asInt()
                                if (itemSe != null && itemSe > 0 && itemSe != season) continue
                                if (itemEp != null && itemEp > 0 && itemEp != episode) continue

                                val link = item["downloadUrl"]?.asText()?.takeIf { it.isNotBlank() }
                                    ?: item["resourceLink"]?.asText()?.takeIf { it.isNotBlank() }
                                    ?: item["sourceUrl"]?.asText()?.takeIf { it.isNotBlank() }
                                if (!link.isNullOrBlank()) {
                                    val res = item["resolutions"]?.asText()
                                        ?: item["resolution"]?.asText()
                                        ?: item["title"]?.asText()
                                        ?: "Resource"
                                    if (emit(link, res)) hasLinks = true
                                }
                            }
                        }
                    }
                }
            }
        }

        return hasLinks
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

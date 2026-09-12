package com.ntv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NtvLive : MainAPI() {
    override var mainUrl = "https://ntv.st"
    override var name = "NTV Live"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/html, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "https://ntv.st/"
        )
        val posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Referer" to "https://ntv.st/"
        )

        // Servers for main page sections
        val servers = listOf("kobra", "dlhd", "raptor", "falcon", "phoenix", "titan", "viper", "zlive")
        // Servers whose event sources are (source, id) pairs resolved via GOAT
        val goatServers = setOf("kobra", "viper", "dlhd")
    }

    override val mainPage = mainPageOf(
        *servers.map { "${mainUrl}/api/get-matches?server=$it&type=both" to "$it Events" }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val mapper = jacksonObjectMapper().registerKotlinModule()

        val textdoc = withContext(Dispatchers.IO) {
            app.get(url, headers = headers).text
        }

        val data: MatchResponse = mapper.readValue(textdoc)
        val items = mutableListOf<HomePageList>()
        val dayItems = mutableListOf<LiveSearchResponse>()

        val allMatches = (data.live ?: emptyList()) + (data.all ?: emptyList())
        val serverLabel = url.substringAfter("server=").substringBefore("&").lowercase().replaceFirstChar { it.uppercase() }

        for (mt in allMatches) {
            val title = mt.title ?: continue
            val sources = mt.sources ?: emptyList()
            if (sources.isEmpty()) continue

            val firstSource = sources.first()
            val href = buildHref(mt, firstSource, url)

            dayItems.add(
                newLiveSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = mt.poster
                    this.posterHeaders = posterHeaders
                }
            )
        }

        if (dayItems.isNotEmpty()) {
            items.add(
                HomePageList(
                    name = "$serverLabel Events",
                    list = dayItems,
                    isHorizontalImages = false
                )
            )
        }

        return newHomePageResponse(list = items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()
        val mapper = jacksonObjectMapper().registerKotlinModule()

        for (server in servers) {
            try {
                val textdoc = withContext(Dispatchers.IO) {
                    app.get("${mainUrl}/api/get-matches?server=$server&type=both", headers = headers).text
                }
                val data: MatchResponse = mapper.readValue(textdoc)
                val allMatches = (data.live ?: emptyList()) + (data.all ?: emptyList())

                for (mt in allMatches) {
                    val title = mt.title ?: continue
                    if (!title.lowercase().contains(q)) continue

                    val sources = mt.sources ?: emptyList()
                    if (sources.isEmpty()) continue

                    val firstSource = sources.first()
                    val href = buildHref(mt, firstSource, "${mainUrl}/api/get-matches?server=$server&type=both")

                    results.add(
                        newLiveSearchResponse(title, href, TvType.Live) {
                            this.posterUrl = mt.poster
                            this.posterHeaders = posterHeaders
                        }
                    )
                }
            } catch (e: Exception) {
                // Skip failed server
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val idParam = url.substringAfter("id=").substringBefore("&").ifEmpty { null }
            ?: url.substringAfter("/").takeIf { it.isNotEmpty() }

        if (idParam == null) return null

        return try {
            val mapper = jacksonObjectMapper().registerKotlinModule()
            val textdoc = withContext(Dispatchers.IO) {
                app.get("${mainUrl}/api/get-matches?server=all&type=both", headers = headers).text
            }
            val data: MatchResponse = mapper.readValue(textdoc)
            val allMatches = (data.live ?: emptyList()) + (data.all ?: emptyList())

            for (mt in allMatches) {
                val id = mt.id ?: continue
                if (id == idParam || url.contains(id)) {
                    return newMovieLoadResponse(
                        mt.title ?: "NTV Event",
                        url,
                        TvType.Live,
                        url
                    ) {
                        this.posterUrl = mt.poster
                        this.posterHeaders = posterHeaders
                        this.plot = mt.category?.let { "Category: $it" } ?: ""
                        this.tags = listOfNotNull(mt.category)
                    }
                }
            }

            newMovieLoadResponse("NTV Live Stream", url, TvType.Live, url) {
                this.posterHeaders = posterHeaders
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // Fast path 1: Direct m3u8 URLs (zlive signed URLs, direct HLS)
        if (data.endsWith(".m3u8", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "NTV HD",
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            return@withContext true
        }

        // Fast path 2: zlive URL -> follow 302 redirect to signed m3u8
        // zlive URLs look like: https://iptv.zlive.st/<slug>
        if (data.contains("zlive.st") || data.contains("iptv.zlive.st")) {
            // The WebView extractor will follow the 302 redirect automatically
            // but we can also try loading it directly first
            try {
                val redirectUrl = withContext(Dispatchers.IO) {
                    app.get(data, headers = headers).url.toString()
                }
                if (redirectUrl.endsWith(".m3u8", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "NTV HD",
                            url = redirectUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                                "Origin" to mainUrl,
                                "Referer" to data
                            )
                        }
                    )
                    return@withContext true
                }
            } catch (e: Exception) {
                // Fall through to WebView extractor
            }
        }

        // Fast path 3: dlhd stream-<num>.php direct URL -> regex iframe+atob -> m3u8
        // No WebView needed - parse directly like server.py does
        val dlhdMatch = Regex("""dlhd\.st/stream/stream-(\d+)\.php""").find(data)
        if (dlhdMatch != null) {
            val num = dlhdMatch.groupValues[1]
            val dlhdUrl = "https://dlhd.st/stream/stream-$num.php"
            // Feed to WebView extractor which handles the iframe->atob->m3u8 chain
            // (the atob result is a .m3u8 that shouldInterceptRequest catches)
            loadExtractor(
                url = dlhdUrl,
                referer = "$mainUrl/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return@withContext true
        }

        // Fast path 4: GOAT slots (kobra/viper/dlhd with source,id pairs)
        // Build the embed.st URL and let the extractor handle it
        if (data.contains("embed.st")) {
            loadExtractor(
                url = data,
                referer = "https://embed.st/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return@withContext true
        }

        // Fast path 5: Data URLs from our main page with source+id encoded
        // These come from load() where we constructed an ntv:<srv>:<src>:<id>:<n> URL
        if (data.startsWith("ntv:")) {
            val parts = data.split(":")
            if (parts.size >= 4) {
                val source = parts[2]
                val sid = parts[3]
                val num = if (parts.size >= 5) parts[4] else "1"
                val goatUrl = "https://embed.st/embed/$source/$sid/$num"
                loadExtractor(
                    url = goatUrl,
                    referer = "https://embed.st/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                return@withContext true
            }
        }

        // Fallback: feed to WebView extractor for any other URL pattern
        loadExtractor(
            url = data,
            referer = "$mainUrl/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        true
    }

    /**
     * Build the href URL for a match source based on its type.
     * Direct URL sources -> the URL itself.
     * (source, id) pairs -> encoded GOAT URL that loadLinks can parse.
     */
    private fun buildHref(mt: Match, source: Source, requestData: String): String {
        // Direct URL source -> use it directly
        if (source.url != null) {
            return source.url!!
        }

        // (source, id) pair -> encode into a data URL that loadLinks can parse
        val src = source.source ?: return ""
        val sid = source.id ?: return ""
        // Format: ntv:<server>:<source>:<id>:1
        val server = requestData.substringAfter("server=").substringBefore("&").lowercase()
        return "ntv:$server:$src:$sid:1"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MatchResponse(
        @JsonProperty("live") val live: List<Match>?,
        @JsonProperty("all") val all: List<Match>?,
        @JsonProperty("success") val success: Boolean?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Match(
        @JsonProperty("id") val id: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("category") val category: String?,
        @JsonProperty("sources") val sources: List<Source>?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("status") val status: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        @JsonProperty("url") val url: String?,
        @JsonProperty("source") val source: String?,
        @JsonProperty("id") val id: String?,
        @JsonProperty("channelName") val channelName: String?
    )
}

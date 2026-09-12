package com.ntv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView-based extractor for resolving NTV embed streams.
 * Handles:
 * - embed.st/embed/<src>/<id>/<n> pages (jwplayer/clappr + video.play() click script)
 * - dlhd iframe->atob->m3u8 chain (sniffed via shouldInterceptRequest)
 * - zlive 302 redirects to signed m3u8 URLs
 * - golf detour via embedhd.st -> base64 -> real GOAT slot
 *
 * Ported from EmbedStreams (Cs-Karma/Streamed), retargeted to embed.st.
 */
open class NtvEmbedExtractor(context: Context) : ExtractorApi() {
    override val name = "NTV"
    override val mainUrl = "https://embed.st"
    override val requiresReferer = true
    private val appContext = context.applicationContext

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoUrl = withContext(Dispatchers.Main) {
                getVideoUrlWithWebView(appContext, url)
            }
            if (videoUrl != null) {
                processVideoUrl(videoUrl, callback)
            } else {
                // Fallback: if WebView didn't find m3u8, try the URL directly
                tryExtractDirectUrl(url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            tryExtractDirectUrl(url, subtitleCallback, callback)
        }
    }

    private suspend fun tryExtractDirectUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Direct HLS",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                        "Origin" to mainUrl
                    )
                }
            )
        }
    }

    private suspend fun getVideoUrlWithWebView(context: Context, url: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val captured = AtomicBoolean(false)

                try {
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Auto-click play button (JWPlayer, Clapy, Flowplayer, etc.)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var btn = document.querySelector('.jw-icon-display, .jw-display-play-container, .vjs-big-play-button, .fp-playButton, .player-btn, [class*="play" i]');
                                                if (btn) { btn.click(); return 'clicked'; }
                                                if (typeof jwplayer !== 'undefined' && jwplayer().play) { jwplayer().play(); return 'jwplayer'; }
                                                if (typeof flowplayer !== 'undefined' && flowplayer().play) { flowplayer().play(); return 'flowplayer'; }
                                                if (typeof player !== 'undefined' && player.play) { player.play(); return 'player_api'; }
                                                var video = document.querySelector('video');
                                                if (video && !video.paused) { video.play(); return 'video_play'; }
                                                var playerArea = document.querySelector('.jwplayer, .flowplayer, .video-js, #player, .player-container');
                                                if (playerArea) {
                                                    var clickEvt = new MouseEvent('click', { bubbles: true });
                                                    playerArea.dispatchEvent(clickEvt);
                                                    return 'dispatched_click';
                                                }
                                                return 'no_player';
                                            } catch(e) { return 'error: ' + e.message; }
                                        })();
                                        """.trimIndent()
                                    ) { _ -> }
                                }, 1500)
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // Intercept .m3u8 and .mpd URLs (the stream manifest)
                                if ((reqUrl.endsWith(".m3u8") || reqUrl.endsWith(".mpd")) && !captured.get()) {
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resumeWith(Result.success(reqUrl))
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            try { destroy() } catch (_: Exception) {}
                                        }, 500)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    // Timeout after 15 seconds (matches plan spec)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            cont.resumeWith(Result.success<String?>(null))
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                    }, 15000)

                } catch (e: Exception) {
                    if (captured.compareAndSet(false, true)) {
                        cont.resumeWith(Result.success<String?>(null))
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post {
                            try { /* cleanup handled */ } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val qualityLabel = when {
            videoUrl.contains("720") -> "720p"
            videoUrl.contains("1080") -> "1080p"
            videoUrl.contains("480") -> "480p"
            videoUrl.contains("360") -> "360p"
            videoUrl.contains("alpha") -> "Alpha-Trusted 720p 30fps"
            videoUrl.contains("bravo") -> "Bravo-High Fps Low Bitrate"
            videoUrl.contains("echo") -> "Echo-Good Quality"
            videoUrl.contains("golf") -> "Golf-High Quality Direct"
            videoUrl.contains("hotel") -> "Hotel-Very High Quality"
            videoUrl.contains("intel") -> "Intel-Wide Coverage"
            else -> name
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = qualityLabel,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive"
                )
            }
        )
    }
}

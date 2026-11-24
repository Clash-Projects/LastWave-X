package com.clashprojects.lastwave.innertube

import android.content.Context
import com.clashprojects.lastwave.innertube.auth.InMemoryTubeCredentialStore
import com.clashprojects.lastwave.innertube.auth.TubeAuthManager
import com.clashprojects.lastwave.innertube.auth.TubeCredentialStore
import com.clashprojects.lastwave.innertube.cipher.CipherConfigStore
import com.clashprojects.lastwave.innertube.cipher.PlayerCipherEngine
import com.clashprojects.lastwave.innertube.extract.AudioQuality
import com.clashprojects.lastwave.innertube.extract.ContentHints
import com.clashprojects.lastwave.innertube.extract.DefaultWatchConfigParser
import com.clashprojects.lastwave.innertube.extract.ExtractedStream
import com.clashprojects.lastwave.innertube.extract.StreamExtractor
import com.clashprojects.lastwave.innertube.extract.StreamResolveException
import com.clashprojects.lastwave.innertube.extract.TokenProvider
import com.clashprojects.lastwave.innertube.extract.WaveStreamExtractor
import com.clashprojects.lastwave.innertube.extract.generateClientPlaybackNonce
import com.clashprojects.lastwave.innertube.net.TubeHttpConfig
import com.clashprojects.lastwave.innertube.net.TubeHttpFactory
import com.clashprojects.lastwave.innertube.potoken.AndroidPoTokenProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

class WaveTubeClient(
    val okHttpClient: OkHttpClient,
    val httpClient: HttpClient,
    val tube: WaveTube,
    val cipherEngine: PlayerCipherEngine,
    val extractor: StreamExtractor,
    val auth: TubeAuthManager,
    private val tokenProvider: TokenProvider? = null,
) {
    // Auto-routes to Premium 256kbps when detected in memory (0ms overhead), with guaranteed fallback to normal playback.
    suspend fun resolveStream(
        videoId: String,
        hints: ContentHints = ContentHints(),
        excludedClients: Set<String> = emptySet(),
        audioQuality: AudioQuality = AudioQuality.AUTO,
        clientPlaybackNonce: String = generateClientPlaybackNonce(),
    ): ExtractedStream? {
        val tryPremium =
            !hints.premium &&
                auth.isSignedIn &&
                auth.isPremium.value &&
                audioQuality != AudioQuality.LOW &&
                audioQuality != AudioQuality.MP4 &&
                hints.playbackClientOverrideId == null

        if (tryPremium) {
            val premiumStream =
                try {
                    extractor.extract(
                        videoId = videoId,
                        hints = hints.withPremium(true),
                        excludedClients = excludedClients,
                        audioQuality = AudioQuality.HIGH,
                        clientPlaybackNonce = clientPlaybackNonce,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StreamResolveException) {
                    if (e.reason == StreamResolveException.Reason.UNAVAILABLE ||
                        e.reason == StreamResolveException.Reason.AGE_RESTRICTED
                    ) {
                        throw e
                    }
                    null
                } catch (_: Exception) {
                    null
                }

            if (premiumStream != null) {
                auth.observeResolvedStream(premiumStream)
                return premiumStream
            }
        }

        val fallback =
            extractor.extract(
                videoId = videoId,
                hints = hints.withPremium(false),
                excludedClients = excludedClients,
                audioQuality = audioQuality,
                clientPlaybackNonce = clientPlaybackNonce,
            )
        if (fallback != null) {
            auth.observeResolvedStream(fallback)
        }
        return fallback
    }

    suspend fun prewarm() {
        (tokenProvider as? AndroidPoTokenProvider)?.prewarm()
        extractor.prewarm()
    }

    suspend fun close() {
        auth.close()
        tube.close()
        cipherEngine.dispose()
        tokenProvider?.close()
        httpClient.close()
    }

    companion object {
        fun create(
            context: Context,
            httpConfig: TubeHttpConfig = TubeHttpConfig(),
            credentialStore: TubeCredentialStore = InMemoryTubeCredentialStore(),
            cipherConfigStore: CipherConfigStore? = null,
            logger: TubeLogger = TubeLogger.NONE,
        ): WaveTubeClient =
            create(
                httpConfig = httpConfig,
                credentialStore = credentialStore,
                cipherConfigStore = cipherConfigStore,
                tokenProvider = AndroidPoTokenProvider(context.applicationContext, logger),
                logger = logger,
            )

        fun create(
            httpConfig: TubeHttpConfig = TubeHttpConfig(),
            credentialStore: TubeCredentialStore = InMemoryTubeCredentialStore(),
            cipherConfigStore: CipherConfigStore? = null,
            tokenProvider: TokenProvider? = null,
            logger: TubeLogger = TubeLogger.NONE,
        ): WaveTubeClient {
            val okHttp = TubeHttpFactory.createOkHttpClient(httpConfig)
            val ktor = TubeHttpFactory.createKtorClient(okHttp, httpConfig)
            val tube = WaveTube(httpClient = ktor, logger = logger)
            val cipher = PlayerCipherEngine(httpClient = ktor, remotePlayerConfigStore = cipherConfigStore, logger = logger)
            val extractor =
                WaveStreamExtractor(
                    configParser =
                        DefaultWatchConfigParser(
                            httpClient = ktor,
                            innerTube = tube,
                            remotePlayerConfigStore = cipherConfigStore,
                            logger = logger,
                            cipherService = cipher,
                        ),
                    cipherService = cipher,
                    innerTube = tube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            val auth = TubeAuthManager(tube = tube, store = credentialStore)
            return WaveTubeClient(
                okHttpClient = okHttp,
                httpClient = ktor,
                tube = tube,
                cipherEngine = cipher,
                extractor = extractor,
                auth = auth,
                tokenProvider = tokenProvider,
            )
        }
    }
}

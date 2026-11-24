package com.clashprojects.lastwave.innertube

import com.clashprojects.lastwave.innertube.auth.InMemoryTubeCredentialStore
import com.clashprojects.lastwave.innertube.auth.TubeAuthManager
import com.clashprojects.lastwave.innertube.auth.TubeCredentialStore
import com.clashprojects.lastwave.innertube.cipher.CipherConfigStore
import com.clashprojects.lastwave.innertube.cipher.PlayerCipherEngine
import com.clashprojects.lastwave.innertube.extract.DefaultWatchConfigParser
import com.clashprojects.lastwave.innertube.extract.TokenProvider
import com.clashprojects.lastwave.innertube.extract.WaveStreamExtractor
import com.clashprojects.lastwave.innertube.net.TubeHttpConfig
import com.clashprojects.lastwave.innertube.net.TubeHttpFactory
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient

class WaveTubeClient(
    val okHttpClient: OkHttpClient,
    val httpClient: HttpClient,
    val tube: WaveTube,
    val cipherEngine: PlayerCipherEngine,
    val extractor: WaveStreamExtractor,
    val auth: TubeAuthManager,
) {
    suspend fun close() {
        tube.close()
        cipherEngine.dispose()
        httpClient.close()
    }

    companion object {
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
            )
        }
    }
}

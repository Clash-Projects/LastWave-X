package com.clashprojects.lastwave.innertube.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class TubeHttpConfig(
    val dohProvider: DohProvider = DohProvider.System,
    val dohFallbackToSystem: Boolean = true,
    val proxy: Proxy? = null,
    val cacheDir: File? = null,
    val cacheSizeBytes: Long = 50L * 1024L * 1024L,
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val requestTimeoutMs: Long = 30_000L,
)

object TubeHttpFactory {
    val defaultJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun createOkHttpClient(config: TubeHttpConfig = TubeHttpConfig()): OkHttpClient {
        val baseBuilder =
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)

        config.proxy?.let { baseBuilder.proxy(it) }
        config.cacheDir?.let { dir ->
            baseBuilder.cache(Cache(dir, config.cacheSizeBytes))
        }

        if (config.dohProvider != DohProvider.System) {
            val bootstrap = baseBuilder.build()
            val dohDns = config.dohProvider.toDns(bootstrap, fallbackToSystem = config.dohFallbackToSystem)
            return bootstrap.newBuilder().dns(dohDns).build()
        }

        return baseBuilder.build()
    }

    fun createKtorClient(
        okHttpClient: OkHttpClient = createOkHttpClient(),
        config: TubeHttpConfig = TubeHttpConfig(),
    ): HttpClient =
        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(defaultJson)
            }
            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.connectTimeoutMs
                socketTimeoutMillis = config.readTimeoutMs
            }
            expectSuccess = false
        }
}

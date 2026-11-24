package com.clashprojects.lastwave.innertube.cipher

import com.clashprojects.lastwave.innertube.bodyAsTextLimited
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess

internal data class RestrictedTextResponse(
    val status: HttpStatusCode,
    val headers: Headers,
    val body: String?,
)

internal fun HttpClient.createNoRedirectClient(): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
        }
    }

internal suspend fun HttpClient.getTextOnDirectClient(
    url: Url,
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): RestrictedTextResponse =
    prepareGet(url, configure).execute { response ->
        if (response.status.isSuccess()) {
            RestrictedTextResponse(response.status, response.headers, response.bodyAsTextLimited(maxBytes, stopWhen))
        } else {
            response.bodyAsChannel().cancel(null)
            RestrictedTextResponse(response.status, response.headers, null)
        }
    }

internal suspend fun HttpClient.getTextWithoutRedirects(
    url: Url,
    maxBytes: Int,
    stopWhen: (suspend (String) -> Boolean)? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): RestrictedTextResponse {
    val directClient = createNoRedirectClient()
    return try {
        directClient.getTextOnDirectClient(url, maxBytes, stopWhen, configure)
    } finally {
        directClient.close()
    }
}

private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
private const val DEFAULT_SOCKET_TIMEOUT_MS = 10_000L

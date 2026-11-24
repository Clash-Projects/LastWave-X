package com.clashprojects.lastwave.innertube

import com.clashprojects.lastwave.innertube.cipher.createNoRedirectClient
import com.clashprojects.lastwave.innertube.model.Context
import com.clashprojects.lastwave.innertube.model.TubeClient
import com.clashprojects.lastwave.innertube.model.TubeLocale
import com.clashprojects.lastwave.innertube.model.request.AccountMenuBody
import com.clashprojects.lastwave.innertube.model.request.AccountsListBody
import com.clashprojects.lastwave.innertube.model.request.Action
import com.clashprojects.lastwave.innertube.model.request.BrowseBody
import com.clashprojects.lastwave.innertube.model.request.CreatePlaylistBody
import com.clashprojects.lastwave.innertube.model.request.DeletePrivatelyOwnedEntityBody
import com.clashprojects.lastwave.innertube.model.request.EditPlaylistBody
import com.clashprojects.lastwave.innertube.model.request.FeedbackBody
import com.clashprojects.lastwave.innertube.model.request.GetQueueBody
import com.clashprojects.lastwave.innertube.model.request.GetSearchSuggestionsBody
import com.clashprojects.lastwave.innertube.model.request.GetTranscriptBody
import com.clashprojects.lastwave.innertube.model.request.LikeBody
import com.clashprojects.lastwave.innertube.model.request.NextBody
import com.clashprojects.lastwave.innertube.model.request.PlayerBody
import com.clashprojects.lastwave.innertube.model.request.PlaylistDeleteBody
import com.clashprojects.lastwave.innertube.model.request.ResolveUrlBody
import com.clashprojects.lastwave.innertube.model.request.SearchBody
import com.clashprojects.lastwave.innertube.model.request.SubscribeBody
import com.clashprojects.lastwave.innertube.model.response.ImageUploadResponse
import com.clashprojects.lastwave.innertube.util.parseCookieString
import com.clashprojects.lastwave.innertube.util.sanitizeCookieString
import com.clashprojects.lastwave.innertube.util.sanitizeVisitorData
import com.clashprojects.lastwave.innertube.util.sha1
import io.ktor.client.HttpClient
import io.ktor.client.call.replaceResponse
import io.ktor.client.call.save
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareHead
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class WaveTube(
    val httpClient: HttpClient,
    private val retryDelay: suspend (kotlin.time.Duration) -> Unit = { delay(it) },
    private val logger: TubeLogger = TubeLogger.NONE,
) {
    private companion object {
        private const val TAG = "WaveTube"
        private const val ORIGIN_WWW = "https://www.youtube.com"
        private const val REFERER_WWW = "$ORIGIN_WWW/"
        private const val API_BASE_WWW = "$ORIGIN_WWW/youtubei/v1"

        private const val ORIGIN_MWEB = "https://m.youtube.com"
        private const val REFERER_MWEB = "$ORIGIN_MWEB/"
        private const val API_BASE_MWEB = "$ORIGIN_MWEB/youtubei/v1"

        private const val ORIGIN_MUSIC = "https://music.youtube.com"
        private const val REFERER_MUSIC = "$ORIGIN_MUSIC/"
        private const val API_BASE_MUSIC = "$ORIGIN_MUSIC/youtubei/v1"
        private const val VISITOR_DATA_URL = "$ORIGIN_WWW/sw.js_data"
        private const val TRANSCRIPT_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        private const val ORIGIN_STUDIO = "https://studio.youtube.com"
        private const val REFERER_STUDIO = "$ORIGIN_STUDIO/"
        private const val API_BASE_STUDIO = "$ORIGIN_STUDIO/youtubei/v1"
        private const val TV_BEARER_PLAYER_ENDPOINT = "$API_BASE_WWW/player"
        private const val TV_BEARER_TIMEOUT_MS = 8_000L
        private const val MAX_API_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_RESOLVE_URL_RESPONSE_BYTES = 256 * 1024
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private const val MAX_TV_BEARER_RESPONSE_BYTES = 4 * 1024 * 1024

        private val TRANSIENT_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private const val MAX_STATS_RESPONSE_BYTES = 64 * 1024
        private val STATS_HOSTS = setOf("s.youtube.com", "www.youtube.com", "music.youtube.com")
        private val STATS_PATHS = setOf("/api/stats/playback", "/api/stats/watchtime")
        private val MEDIA_REQUEST_HEADERS =
            setOf(
                HttpHeaders.Accept.lowercase(),
                HttpHeaders.AcceptLanguage.lowercase(),
                HttpHeaders.Range.lowercase(),
                HttpHeaders.UserAgent.lowercase(),
            )
        private val VISITOR_DATA_REGEX = Regex(""""(?:VISITOR_DATA|visitorData)"\s*:\s*"([^"]+)"""")
        private const val MAX_UPLOAD_START_RESPONSE_BYTES = 64 * 1024
        private const val MAX_IMAGE_UPLOAD_RESPONSE_BYTES = 64 * 1024
        private val IMAGE_UPLOAD_JSON = Json { ignoreUnknownKeys = true }
        private const val MAX_UPLOAD_BYTES = 300L * 1024 * 1024
        private const val VISITOR_FETCH_TIMEOUT_MS = 15_000L
        private const val SERVICE_WORKER_VISITOR_TIMEOUT_MS = 4_000L
        private const val FIELD_MASK_HEADER = "X-Goog-FieldMask"
        private val VISITOR_FAILURE_COOLDOWN = 30.seconds
        private const val MAX_VISITOR_RESPONSE_BYTES = 4 * 1024 * 1024
        private val CLOSED_CLIENT = Any()
        private val NO_STREAM_RESULT = Any()
    }

    // These requests are POSTs. Replace even an explicitly installed caller redirect policy.
    private val postClient =
        httpClient.config {
            followRedirects = false
            installOrReplace(HttpRedirect) { checkHttpMethod = true }
        }

    private val noRedirectClientState = AtomicReference<Any?>(null)
    internal val noRedirectClient: HttpClient
        get() {
            while (true) {
                when (val state = noRedirectClientState.load()) {
                    is HttpClient -> {
                        return state
                    }

                    CLOSED_CLIENT -> {
                        throw CancellationException("WaveTube is closed")
                    }

                    else -> {
                        val created = httpClient.createNoRedirectClient()
                        if (noRedirectClientState.compareAndSet(null, created)) {
                            return created
                        }
                        created.close()
                    }
                }
            }
        }

    private data class PrefCookieCache(
        val cookie: String?,
        val hl: String,
        val gl: String,
        val value: String,
    )

    private val cachedPrefCookie = AtomicReference<PrefCookieCache?>(null)

    private fun effectivePrefCookie(
        cookie: String?,
        hl: String,
        gl: String,
    ): String {
        cachedPrefCookie
            .load()
            ?.takeIf { it.cookie == cookie && it.hl == hl && it.gl == gl }
            ?.let { return it.value }
        val built = injectPrefCookie(cookie, hl, gl)
        cachedPrefCookie.store(PrefCookieCache(cookie, hl, gl, built))
        return built
    }

    data class TubeSession(
        val locale: TubeLocale =
            com.clashprojects.lastwave.innertube.model
                .defaultTubeLocale(),
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val authUser: String = "0",
        val cookie: String? = null,
        val sapisid: String? = null,
        val useLoginForBrowse: Boolean = false,
        val regionOverrideActive: Boolean = false,
        val generation: Long = 0,
    ) {
        override fun toString(): String =
            "SessionSnapshot(" +
                "locale=$locale, " +
                "visitorData=${visitorData.presence()}, " +
                "dataSyncId=${dataSyncId.presence()}, " +
                "authUser=${authUser.presence()}, " +
                "cookie=${cookie.presence()}, " +
                "sapisid=${sapisid.presence()}, " +
                "useLoginForBrowse=$useLoginForBrowse, " +
                "regionOverrideActive=$regionOverrideActive, " +
                "generation=$generation)"

        private fun String?.presence(): String = if (isNullOrBlank()) "missing" else "present"
    }

    private data class VisitorDataFlight(
        val sessionIdentity: TubeSession,
        val result: CompletableDeferred<String?>,
    )

    private data class SessionBoundRequest(
        val session: TubeSession,
        val job: Job,
    )

    private val session = AtomicReference(TubeSession())
    private val mutableSessionFlow = MutableStateFlow(session.load())
    val sessionFlow: StateFlow<TubeSession> = mutableSessionFlow.asStateFlow()

    private val visitorDataFetchMutex = Mutex()
    private val visitorDataScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visitorDataFlight: VisitorDataFlight? = null

    @Volatile
    private var playerFieldMaskEnabled = true
    private var visitorDataFailure: Pair<TubeSession, TimeSource.Monotonic.ValueTimeMark>? = null
    private val sessionBoundRequests = AtomicReference<List<SessionBoundRequest>>(emptyList())

    var locale: TubeLocale
        get() = session.load().locale
        set(value) = updateSession { it.copy(locale = value) }

    var visitorData: String?
        get() = session.load().visitorData
        set(value) = updateSession { it.copy(visitorData = sanitizeVisitorData(value)) }

    var dataSyncId: String?
        get() = session.load().dataSyncId
        set(value) = updateSession { it.copy(dataSyncId = value) }

    var authUser: String
        get() = session.load().authUser
        set(value) = updateSession { it.copy(authUser = sanitizeAuthUser(value)) }

    var cookie: String?
        get() = session.load().cookie
        set(value) {
            val sanitized = value?.let(::sanitizeCookieString)
            updateSession { it.copy(cookie = sanitized, sapisid = resolveSapisidValue(sanitized)) }
        }

    var useLoginForBrowse: Boolean
        get() = session.load().useLoginForBrowse
        set(value) = updateSession { it.copy(useLoginForBrowse = value) }

    var regionOverrideActive: Boolean
        get() = session.load().regionOverrideActive
        set(value) = updateSession { it.copy(regionOverrideActive = value) }

    fun createIsolatedSession(includeAccount: Boolean): WaveTube =
        WaveTube(httpClient, retryDelay, logger).also { isolated ->
            isolated.locale = locale
            isolated.regionOverrideActive = regionOverrideActive
            sessionSnapshot().let { current ->
                isolated.replaceSession(
                    cookie = current.cookie.takeIf { includeAccount },
                    visitorData = current.visitorData,
                    dataSyncId = current.dataSyncId.takeIf { includeAccount },
                    authUser = current.authUser.takeIf { includeAccount } ?: "0",
                    useLoginForBrowse = current.useLoginForBrowse && includeAccount,
                )
            }
        }

    fun close() {
        visitorDataScope.cancel()
        postClient.close()
        while (true) {
            val state = noRedirectClientState.load()
            if (state === CLOSED_CLIENT) break
            if (noRedirectClientState.compareAndSet(state, CLOSED_CLIENT)) {
                (state as? HttpClient)?.close()
                break
            }
        }
        while (true) {
            val requests = sessionBoundRequests.load()
            if (sessionBoundRequests.compareAndSet(requests, emptyList())) {
                requests.forEach { it.job.cancel() }
                return
            }
        }
    }

    private inline fun updateSession(transform: (TubeSession) -> TubeSession) {
        while (true) {
            val current = session.load()
            val updated = transform(current)
            if (updated == current) return
            if (publishSession(current, updated)) return
        }
    }

    fun sessionSnapshot(): TubeSession = session.load()

    fun sessionSnapshotWithVisitorData(
        expected: TubeSession,
        visitorData: String,
    ): TubeSession? {
        val current = session.load()
        return current.takeIf { it.visitorData == visitorData && sessionIsCompatible(expected, it) }
    }

    fun clearSessionIfMatches(expected: TubeSession): Boolean =
        publishSession(
            expected,
            expected.copy(
                visitorData = null,
                dataSyncId = null,
                authUser = "0",
                cookie = null,
                sapisid = null,
                useLoginForBrowse = false,
            ),
        )

    private fun publishSession(
        expected: TubeSession,
        updated: TubeSession,
    ): Boolean {
        val current = session.load()
        if (updated == expected) return current == expected
        // Callers pass value copies, so match by equality; the CAS below still guards against races.
        if (current != expected) return false
        val versioned = updated.copy(generation = expected.generation + 1)
        if (!session.compareAndSet(current, versioned)) return false
        while (true) {
            val published = mutableSessionFlow.value
            if (published.generation >= versioned.generation) break
            if (mutableSessionFlow.compareAndSet(published, versioned)) break
        }
        cancelStaleSessionRequests(versioned)
        return true
    }

    private fun sessionIsCompatible(
        expected: TubeSession,
        current: TubeSession,
    ): Boolean =
        expected.generation == current.generation ||
            (
                expected.visitorData == null && current.visitorData != null &&
                    current.generation == expected.generation + 1 &&
                    expected.copy(visitorData = current.visitorData, generation = current.generation) == current
            )

    private fun cancelStaleSessionRequests(current: TubeSession) {
        while (true) {
            val requests = sessionBoundRequests.load()
            val (active, stale) =
                requests.partition {
                    it.session.generation > current.generation ||
                        sessionIsCompatible(it.session, current)
                }
            if (!sessionBoundRequests.compareAndSet(requests, active)) continue
            stale.forEach { it.job.cancel(CancellationException("WaveTube session changed")) }
            return
        }
    }

    private suspend fun <T> withSessionBoundRequest(
        requestSession: TubeSession,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            val request = SessionBoundRequest(requestSession, job)
            while (true) {
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("WaveTube session changed")
                val requests = sessionBoundRequests.load()
                if (sessionBoundRequests.compareAndSet(requests, requests + request)) break
            }
            try {
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("WaveTube session changed")
                val result = block()
                currentCoroutineContext().ensureActive()
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("WaveTube session changed")
                result
            } finally {
                while (true) {
                    val requests = sessionBoundRequests.load()
                    if (request !in requests || sessionBoundRequests.compareAndSet(requests, requests - request)) break
                }
            }
        }

    fun replaceSession(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
        authUser: String,
        useLoginForBrowse: Boolean,
    ) {
        val sanitizedCookie = cookie?.let(::sanitizeCookieString)
        updateSession { current ->
            current.copy(
                visitorData = sanitizeVisitorData(visitorData),
                dataSyncId = dataSyncId,
                authUser = sanitizeAuthUser(authUser),
                cookie = sanitizedCookie,
                sapisid = resolveSapisidValue(sanitizedCookie),
                useLoginForBrowse = useLoginForBrowse,
            )
        }
    }

    private fun sanitizeAuthUser(value: String): String = value.filter { it.isDigit() }.ifBlank { "0" }

    private fun resolveSapisidValue(cookie: String?): String? {
        val cookieMap = cookie?.let(::parseCookieString).orEmpty()
        return cookieMap["SAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-1PAPISID"]?.takeIf { it.isNotBlank() }
    }

    private fun sapisidAuthHeader(
        origin: String,
        sapisid: String?,
    ): String? {
        sapisid ?: return null
        val currentTime = Clock.System.now().toEpochMilliseconds() / 1000
        val sapisidHash = sha1("$currentTime $sapisid $origin")
        return "SAPISIDHASH ${currentTime}_$sapisidHash"
    }

    fun hasSapCookieAuth(): Boolean = hasSapCookieAuth(session.load())

    fun hasSapCookieAuth(session: TubeSession): Boolean = !session.cookie.isNullOrBlank() && !session.sapisid.isNullOrBlank()

    private fun HttpRequestBuilder.uploadAuthHeaders(session: TubeSession) {
        userAgent(TubeClient.WEB_REMIX.userAgent)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", TubeClient.WEB_REMIX.clientId)
            append("X-YouTube-Client-Version", TubeClient.WEB_REMIX.clientVersion)
            append("Origin", ORIGIN_MUSIC)
            append("X-Origin", ORIGIN_MUSIC)
            append("Referer", REFERER_MUSIC)
            append("X-Goog-AuthUser", session.authUser)
            append("Accept-Language", session.locale.acceptLanguageHeader())
            session.visitorData?.let { append("X-Goog-Visitor-Id", it) }
            val effectiveCookie = effectivePrefCookie(session.cookie, session.locale.hl, session.locale.gl)
            append(HttpHeaders.Cookie, effectiveCookie)
            sapisidAuthHeader(ORIGIN_MUSIC, session.sapisid)?.let { append(HttpHeaders.Authorization, it) }
        }
    }

    private fun HttpRequestBuilder.ytClient(
        client: TubeClient,
        session: TubeSession,
        setLogin: Boolean = false,
        visitorData: String? = session.visitorData,
        includeAccountCookies: Boolean = true,
    ) {
        val endpoint = url.encodedPath.removePrefix("/")
        val (apiBase, origin, referer) =
            when {
                endpoint == "player" && client.useMusicPlayerEndpoint -> {
                    Triple(API_BASE_MUSIC, ORIGIN_MUSIC, REFERER_MUSIC)
                }

                client.clientName == "WEB_REMIX" -> {
                    Triple(API_BASE_MUSIC, ORIGIN_MUSIC, REFERER_MUSIC)
                }

                client.clientName == "WEB_CREATOR" -> {
                    Triple(API_BASE_STUDIO, ORIGIN_STUDIO, REFERER_STUDIO)
                }

                client.clientName == "MWEB" -> {
                    Triple(API_BASE_MWEB, ORIGIN_MWEB, REFERER_MWEB)
                }

                else -> {
                    Triple(API_BASE_WWW, ORIGIN_WWW, REFERER_WWW)
                }
            }
        val requestReferer = if (client.isEmbedded) "https://www.reddit.com/" else referer

        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("Origin", origin)
            append("X-Origin", origin)
            append("Referer", requestReferer)
            append(
                "Accept-Language",
                session.locale.acceptLanguageHeader(),
            )
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (client.loginSupported && includeAccountCookies) {
                val effectiveCookie = effectivePrefCookie(session.cookie, session.locale.hl, session.locale.gl)
                append(HttpHeaders.Cookie, effectiveCookie)
            }
            if (setLogin && client.loginSupported && !session.cookie.isNullOrBlank()) {
                append("X-Goog-AuthUser", session.authUser)
                val sapisid = session.sapisid
                if (!sapisid.isNullOrBlank()) {
                    sapisidAuthHeader(origin, sapisid)?.let { append(HttpHeaders.Authorization, it) }
                }
            }
        }

        url("$apiBase/$endpoint")
        parameter("prettyPrint", false)
        userAgent(client.userAgent)
    }

    private suspend fun <T> withNoRedirects(block: suspend (HttpClient) -> T): T = block(noRedirectClient)

    private suspend fun HttpClient.postBounded(
        url: String,
        maxBytes: Int = MAX_API_RESPONSE_BYTES,
        keepErrorBody: Boolean = true,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        preparePost(url) {
            block()
            expectSuccess = false
        }.executeBounded(maxBytes, keepErrorBody)

    private suspend fun HttpClient.getBounded(
        url: String,
        maxBytes: Int = MAX_API_RESPONSE_BYTES,
        keepErrorBody: Boolean = true,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        prepareGet(url) {
            block()
            expectSuccess = false
        }.executeBounded(maxBytes, keepErrorBody)

    private suspend fun HttpStatement.executeBounded(
        maxBytes: Int,
        keepErrorBody: Boolean,
    ): HttpResponse =
        execute { response ->
            val channel = response.bodyAsChannel()
            try {
                if (!keepErrorBody && !response.status.isSuccess()) {

                    return@execute response.call
                        .replaceResponse { ByteReadChannel.Empty }
                        .save()
                        .response
                }
                check((response.contentLength() ?: 0L) <= maxBytes) { "Response exceeded the $maxBytes byte limit" }
                val bytes = channel.readRemaining(maxBytes.toLong() + 1).readByteArray()
                check(bytes.size <= maxBytes) { "Response exceeded the $maxBytes byte limit" }

                response.call
                    .replaceResponse { ByteReadChannel(bytes) }
                    .save()
                    .response
            } finally {
                channel.cancel(null)
            }
        }

    private suspend fun <T> executeStreamingRequest(
        operation: String,
        retryTransientFailures: Boolean = true,
        requireSuccess: Boolean = true,
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        prepare: suspend () -> HttpStatement,
        onResponse: suspend (HttpResponse) -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                var retryAfterMs: Long? = null
                var shouldRetry = false
                var resultValue: Any? = NO_STREAM_RESULT
                prepare().execute { response ->
                    val statusCode = response.status.value
                    retryAfterMs =
                        if (statusCode == 429) {
                            response.headers[HttpHeaders.RetryAfter]
                                ?.toLongOrNull()
                                ?.times(1_000L)
                                ?.takeIf { it in 0..MAX_RETRY_AFTER_MS }
                        } else {
                            null
                        }
                    val retryableStatus = statusCode in TRANSIENT_STATUS_CODES && (statusCode != 429 || retryAfterMs != null)
                    if (!retryTransientFailures || !retryableStatus || attempt + 1 >= maxAttempts) {
                        if (attempt > 0 && response.status.isSuccess()) {
                            logger.w(TAG, "$operation succeeded on retry ${attempt + 1}/$maxAttempts")
                        }
                        if (requireSuccess && statusCode !in 200..299) {
                            response.bodyAsChannel().cancel(null)
                            throw TubeHttpException(operation, response.status)
                        }
                        resultValue = onResponse(response)
                        return@execute
                    }
                    response.bodyAsChannel().cancel(null)
                    shouldRetry = true
                    attempt++
                    logger.w(
                        TAG,
                        "$operation returned HTTP $statusCode on attempt $attempt/$maxAttempts, retrying in ${retryAfterMs ?: currentDelay}ms",
                    )
                }
                if (!shouldRetry) {
                    @Suppress("UNCHECKED_CAST")
                    return resultValue as T
                }
                retryDelay((retryAfterMs ?: currentDelay).milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TubeHttpException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (!retryTransientFailures || (e !is IOException && e !is HttpRequestTimeoutException) || attempt >= maxAttempts) {
                    logger.w(TAG, "$operation failed after $attempt attempts (${e.logType()})")
                    throw e
                }
                logger.w(
                    TAG,
                    "$operation failed on attempt $attempt/$maxAttempts (${e.logType()}), retrying in ${currentDelay}ms",
                )
                retryDelay(currentDelay.milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private suspend fun executeRequest(
        operation: String = "request",
        requestSession: TubeSession? = null,
        retryTransientFailures: Boolean = true,
        requireSuccess: Boolean = true,
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        if (requestSession != null) {
            return withSessionBoundRequest(requestSession) {
                executeRequest(operation, null, retryTransientFailures, requireSuccess, maxAttempts, initialDelay, factor, block)
            }
        }
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                val response = block()
                // A 429 is only retried when the server says how long to wait, and not for long.
                val retryAfterMs =
                    if (response.status.value == 429) {
                        response.headers[HttpHeaders.RetryAfter]
                            ?.toLongOrNull()
                            ?.times(1_000L)
                            ?.takeIf { it in 0..MAX_RETRY_AFTER_MS }
                    } else {
                        null
                    }
                val retryableStatus =
                    response.status.value in TRANSIENT_STATUS_CODES && (response.status.value != 429 || retryAfterMs != null)
                if (!retryTransientFailures || !retryableStatus || attempt + 1 >= maxAttempts) {
                    if (attempt > 0 && response.status.isSuccess()) {
                        logger.w(TAG, "$operation succeeded on retry ${attempt + 1}/$maxAttempts")
                    }
                    if (requireSuccess && response.status.value !in 200..299) {
                        response.bodyAsChannel().cancel(null)
                        throw TubeHttpException(operation, response.status)
                    }
                    return response
                }
                response.bodyAsChannel().cancel(null)
                attempt++
                logger.w(
                    TAG,
                    "$operation returned HTTP ${response.status.value} on attempt $attempt/$maxAttempts, retrying in ${retryAfterMs ?: currentDelay}ms",
                )
                retryDelay((retryAfterMs ?: currentDelay).milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TubeHttpException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (!retryTransientFailures || (e !is IOException && e !is HttpRequestTimeoutException) || attempt >= maxAttempts) {
                    logger.w(TAG, "$operation failed after $attempt attempts (${e.logType()})")
                    throw e
                }
                logger.w(
                    TAG,
                    "$operation failed on attempt $attempt/$maxAttempts (${e.logType()}), retrying in ${currentDelay}ms",
                )
                retryDelay(currentDelay.milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private suspend fun executeMutation(
        operation: String,
        requestSession: TubeSession = sessionSnapshot(),
        block: suspend (TubeSession) -> HttpResponse,
    ): HttpResponse =
        withSessionBoundRequest(requestSession) {
            executeRequest(operation = operation, retryTransientFailures = false, requireSuccess = false) {
                block(requestSession)
            }
        }

    suspend fun browse(
        client: TubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = setLogin || requestSession.useLoginForBrowse
        val visitorDataForRequest = requestSession.visitorData.takeUnless { requestSession.regionOverrideActive }
        return executeRequest(
            operation = "browse",
            requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported },
        ) {
            postClient.postBounded("browse", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    visitorData = visitorDataForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    BrowseBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                visitorDataForRequest,
                                if (setLoginForRequest) requestSession.dataSyncId else null,
                            ),
                        browseId = browseId,
                        params = params,
                        continuation = continuation,
                    ),
                )
            }
        }
    }

    internal suspend fun currentMusicVideoId(videoId: String): String? {
        if (!VIDEO_ID.matches(videoId)) return null
        val requestSession = sessionSnapshot()
        val body =
            executeStreamingRequest(
                operation = "resolve_url",
                retryTransientFailures = false,
                prepare = {
                    postClient.preparePost("navigation/resolve_url") {
                        expectSuccess = false
                        ytClient(TubeClient.WEB_REMIX, requestSession, includeAccountCookies = false)
                        setBody(
                            ResolveUrlBody(
                                context = TubeClient.WEB_REMIX.toContext(requestSession.locale, requestSession.visitorData, null),
                                url = "https://music.youtube.com/watch?v=$videoId",
                            ),
                        )
                    }
                },
                onResponse = { response -> response.bodyAsTextLimited(MAX_RESOLVE_URL_RESPONSE_BYTES) },
            )
        val resolved =
            runCatching {
                IMAGE_UPLOAD_JSON
                    .parseToJsonElement(body)
                    .jsonObject["endpoint"]
                    ?.jsonObject
                    ?.get("watchEndpoint")
                    ?.jsonObject
                    ?.get("videoId")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        return resolved?.takeIf { it != videoId && VIDEO_ID.matches(it) }
    }

    suspend fun player(
        client: TubeClient,
        videoId: String,
        playlistId: String? = null,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        requestVisitorData: String? = null,
        encryptedHostFlags: String? = null,
    ): HttpResponse =
        playerWithSessionBound(
            client,
            videoId,
            playlistId,
            signatureTimestamp,
            poToken,
            requestVisitorData,
            sessionSnapshot(),
            encryptedHostFlags,
        )

    private fun TubeClient.withLanguage(language: String): TubeClient =
        if ("{language}" in userAgent) copy(userAgent = userAgent.replace("{language}", language)) else this

    private fun buildPlayerBody(
        client: TubeClient,
        context: Context,
        videoId: String,
        signatureTimestamp: Int?,
        playlistId: String? = null,
        poToken: String? = null,
        encryptedHostFlags: String? = null,
        thirdParty: PlayerBody.ThirdParty? = null,
    ): PlayerBody =
        PlayerBody(
            context = context,
            videoId = videoId,
            playlistId = playlistId,
            playbackContext =
                if (client.useSignatureTimestamp || client.isEmbedded) {
                    PlayerBody.PlaybackContext(
                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                            html5Preference = "HTML5_PREF_WANTS".takeUnless { client.useMusicPlayerEndpoint },
                            signatureTimestamp = signatureTimestamp.takeIf { client.useSignatureTimestamp },
                            encryptedHostFlags = encryptedHostFlags.takeIf { client.isEmbedded },
                        ),
                    )
                } else {
                    null
                },
            thirdParty = thirdParty,
            serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
            contentCheckOk = true,
            racyCheckOk = true,
            videoCheckOk = true.takeUnless { client.useMusicPlayerEndpoint },
        )

    internal suspend fun playerWithSession(
        client: TubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String? = null,
        retryTransientFailures: Boolean = true,
    ): HttpResponse =
        executeRequest(operation = "player:${client.clientName}", retryTransientFailures = retryTransientFailures) {
            timedPlayerRequest(client) {
                postClient.postBounded("player", keepErrorBody = false) {
                    playerRequest(
                        client,
                        videoId,
                        playlistId,
                        signatureTimestamp,
                        poToken,
                        requestVisitorData,
                        requestSession,
                        encryptedHostFlags,
                    )
                }
            }
        }

    internal suspend fun playerTextWithSessionBound(
        client: TubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String?,
        maxBytes: Int,
    ): String =
        withSessionBoundRequest(requestSession) {
            timedPlayerRequest(client) {
                val masked = playerFieldMaskEnabled
                try {
                    extractionPlayerText(client, videoId, signatureTimestamp, poToken, requestSession, encryptedHostFlags, maxBytes, masked)
                } catch (e: TubeHttpException) {
                    if (!masked || e.status != HttpStatusCode.BadRequest) throw e

                    extractionPlayerText(
                        client,
                        videoId,
                        signatureTimestamp,
                        poToken,
                        requestSession,
                        encryptedHostFlags,
                        maxBytes,
                        false,
                    ).also {
                        playerFieldMaskEnabled = false
                        logger.w(TAG, "player field mask rejected, disabled for this instance")
                    }
                }
            }
        }

    private suspend fun extractionPlayerText(
        client: TubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String?,
        maxBytes: Int,
        fieldMask: Boolean,
    ): String =
        postClient
            .preparePost("player") {
                playerRequest(client, videoId, null, signatureTimestamp, poToken, null, requestSession, encryptedHostFlags)

                if (fieldMask) {
                    val mask = if (client.clientName == "TVHTML5") PLAYER_RESPONSE_TV_FIELD_MASK else PLAYER_RESPONSE_FIELD_MASK
                    header(FIELD_MASK_HEADER, mask)
                }
                expectSuccess = false
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    response.bodyAsChannel().cancel(null)
                    throw TubeHttpException("player:${client.clientName}", response.status)
                }
                response.bodyAsTextLimited(maxBytes)
            }

    private inline fun <T> timedPlayerRequest(
        client: TubeClient,
        block: () -> T,
    ): T {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val result = block()
        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
        logger.d(TAG, "player response client=${client.clientName} elapsed=${elapsed}ms")
        return result
    }

    private fun HttpRequestBuilder.playerRequest(
        client: TubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String?,
    ) {
        val visitorDataForRequest = requestVisitorData ?: requestSession.visitorData
        val requestClient = client.withLanguage(requestSession.locale.hl)
        logger.d(
            TAG,
            "player request client=${client.clientName} version=${client.clientVersion} " +
                "poToken=${poToken != null} sts=${signatureTimestamp != null} " +
                "loginSupported=${client.loginSupported} loginRequired=${client.loginRequired} " +
                "hasCookie=${!requestSession.cookie.isNullOrBlank()} hasSapisid=${!requestSession.sapisid.isNullOrBlank()} " +
                "authEligible=${client.loginSupported} hasDataSync=${!requestSession.dataSyncId.isNullOrBlank()}",
        )
        ytClient(requestClient, requestSession, setLogin = true, visitorData = visitorDataForRequest)
        setBody(
            buildPlayerBody(
                client = requestClient,
                context =
                    requestClient.toContext(
                        requestSession.locale,
                        visitorDataForRequest,
                        requestSession.dataSyncId.takeIf { client.loginSupported },
                    ),
                videoId = videoId,
                signatureTimestamp = signatureTimestamp,
                playlistId = playlistId,
                poToken = poToken,
                encryptedHostFlags = encryptedHostFlags,
                thirdParty = if (client.isEmbedded) PlayerBody.ThirdParty("https://www.youtube.com/embed/$videoId") else null,
            ),
        )
        userAgent(requestClient.userAgent)
    }

    suspend fun mediaContentLength(
        url: String,
        requestHeaders: Map<String, String>,
    ): Long? =
        try {
            val mediaUrl = validatedMediaUrl(url)
            withNoRedirects { directClient ->
                directClient
                    .prepareHead(mediaUrl) {
                        expectSuccess = false
                        requestHeaders.forEach { (name, value) ->
                            if (name.lowercase() in MEDIA_REQUEST_HEADERS) header(name, value)
                        }
                    }.execute { response ->
                        response.contentLength()?.takeIf { response.status.isSuccess() && it > 0L }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun playerWithSessionBound(
        client: TubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String? = null,
    ): HttpResponse =
        playerWithSessionBound(
            client,
            videoId,
            playlistId,
            signatureTimestamp,
            poToken,
            requestVisitorData,
            requestSession,
            encryptedHostFlags,
            retryTransientFailures = true,
        )

    internal suspend fun playerWithSessionBound(
        client: TubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: TubeSession,
        encryptedHostFlags: String?,
        retryTransientFailures: Boolean,
    ): HttpResponse =
        withSessionBoundRequest(requestSession) {
            playerWithSession(
                client,
                videoId,
                playlistId,
                signatureTimestamp,
                poToken,
                requestVisitorData,
                requestSession,
                encryptedHostFlags,
                retryTransientFailures,
            )
        }

    internal suspend fun playerWithTvBearerSessionBound(
        client: TubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        requestSession: TubeSession,
        bearerToken: String,
    ): String? =
        withSessionBoundRequest(requestSession) {
            require(bearerToken.length in 1..4096 && bearerToken.all { it.code in 0x21..0x7e }) {
                "TV bearer credential is not header-safe"
            }
            val requestClient = client.withLanguage(requestSession.locale.hl)
            val body =
                buildPlayerBody(
                    client = requestClient,
                    context = requestClient.toContext(requestSession.locale, requestSession.visitorData, null),
                    videoId = videoId,
                    signatureTimestamp = signatureTimestamp,
                )
            noRedirectClient
                .preparePost(TV_BEARER_PLAYER_ENDPOINT) {
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    headers {
                        append("X-Goog-Api-Format-Version", "1")
                        append("X-YouTube-Client-Name", requestClient.clientId)
                        append("X-YouTube-Client-Version", requestClient.clientVersion)
                        append("Origin", ORIGIN_WWW)
                        append("X-Origin", ORIGIN_WWW)
                        append("Referer", REFERER_WWW)
                        append("Accept-Language", requestSession.locale.acceptLanguageHeader())
                        requestSession.visitorData?.takeIf(String::isNotBlank)?.let { append("X-Goog-Visitor-Id", it) }
                        append(HttpHeaders.Authorization, "Bearer $bearerToken")
                    }
                    parameter("prettyPrint", false)
                    setBody(Json.encodeToString(PlayerBody.serializer(), body))
                    userAgent(requestClient.userAgent)
                    timeout {
                        requestTimeoutMillis = TV_BEARER_TIMEOUT_MS
                        connectTimeoutMillis = TV_BEARER_TIMEOUT_MS
                        socketTimeoutMillis = TV_BEARER_TIMEOUT_MS
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        response.bodyAsChannel().cancel(null)
                        null
                    } else {
                        response.bodyAsTextLimited(MAX_TV_BEARER_RESPONSE_BYTES)
                    }
                }
        }

    suspend fun next(
        client: TubeClient,
        videoId: String? = null,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = requestSession.useLoginForBrowse
        return executeRequest(operation = "next", requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported }) {
            postClient.postBounded("next", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    NextBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                requestSession.visitorData,
                                requestSession.dataSyncId.takeIf { setLoginForRequest },
                            ),
                        videoId = videoId,
                        playlistId = playlistId,
                        playlistSetVideoId = playlistSetVideoId,
                        index = index,
                        params = params,
                        continuation = continuation,
                        contentCheckOk = true,
                        racyCheckOk = true,
                    ),
                )
            }
        }
    }

    suspend fun registerPlayback(
        client: TubeClient,
        url: String,
        cpn: String,
        playlistId: String? = null,
        currentMediaTime: String? = null,
        final: Boolean? = null,
        format: Int? = null,
    ): HttpResponse =
        registerPlaybackWithSession(
            client = client,
            url = url,
            cpn = cpn,
            playlistId = playlistId,
            requestSession = sessionSnapshot(),
            currentMediaTime = currentMediaTime,
            final = final,
            format = format,
        )

    suspend fun registerPlaybackWithSession(
        client: TubeClient,
        url: String,
        cpn: String,
        playlistId: String?,
        requestSession: TubeSession,
        currentMediaTime: String? = null,
        final: Boolean? = null,
        format: Int? = null,
    ): HttpResponse =
        registerStatsWithSession(
            operation = "registerPlayback",
            client = client,
            url = url,
            cpn = cpn,
            requestSession = requestSession,
            playlistId = playlistId,
            currentMediaTime = currentMediaTime,
            final = final,
            format = format,
        )

    suspend fun registerWatchtimeWithSession(
        client: TubeClient,
        url: String,
        cpn: String,
        startTimes: String,
        endTimes: String,
        currentMediaTime: String,
        realTime: String,
        final: Boolean,
        format: Int?,
        requestSession: TubeSession,
    ): HttpResponse =
        registerStatsWithSession(
            operation = "registerWatchtime",
            client = client,
            url = url,
            cpn = cpn,
            requestSession = requestSession,
            startTimes = startTimes,
            endTimes = endTimes,
            currentMediaTime = currentMediaTime,
            realTime = realTime,
            final = final,
            format = format,
        )

    private suspend fun registerStatsWithSession(
        operation: String,
        client: TubeClient,
        url: String,
        cpn: String,
        requestSession: TubeSession,
        playlistId: String? = null,
        startTimes: String? = null,
        endTimes: String? = null,
        currentMediaTime: String? = null,
        realTime: String? = null,
        final: Boolean? = null,
        format: Int? = null,
    ): HttpResponse {
        val origin = if (client.clientName == "WEB_REMIX") ORIGIN_MUSIC else ORIGIN_WWW
        val statsUrl = validatedStatsUrl(url)
        return withSessionBoundRequest(requestSession) {
            executeRequest(operation = operation, retryTransientFailures = false, requireSuccess = false) {
                withNoRedirects { directClient ->
                    directClient.getBounded(statsUrl.toString(), MAX_STATS_RESPONSE_BYTES) {
                        userAgent(client.userAgent)
                        headers {
                            append("X-Goog-Api-Format-Version", "1")
                            append("X-YouTube-Client-Name", client.clientId)
                            append("X-YouTube-Client-Version", client.clientVersion)
                            append("Origin", origin)
                            append("X-Origin", origin)
                            append("Referer", if (client.clientName == "WEB_REMIX") REFERER_MUSIC else REFERER_WWW)
                            append(
                                "Accept-Language",
                                requestSession.locale.acceptLanguageHeader(),
                            )
                            append("Cache-Control", "no-cache")
                            append("Content-Type", "application/json")
                            requestSession.visitorData?.let { append("X-Goog-Visitor-Id", it) }
                            if (client.loginSupported) {
                                requestSession.cookie?.let { cookieValue ->
                                    append("Cookie", cookieValue)
                                    append("X-Goog-AuthUser", requestSession.authUser)
                                    val sapisid = requestSession.sapisid
                                    if (!sapisid.isNullOrBlank()) {
                                        sapisidAuthHeader(origin, sapisid)?.let { append(HttpHeaders.Authorization, it) }
                                    }
                                }
                            }
                        }
                        url {
                            parameters.append("prettyPrint", "false")
                            parameters.append("ver", "2")
                            parameters.append("c", client.clientName)
                            parameters.remove("cpn")
                            parameters.append("cpn", cpn)
                            startTimes?.let { parameters.append("st", it) }
                            endTimes?.let { parameters.append("et", it) }
                            currentMediaTime?.let { parameters.append("cmt", it) }
                            realTime?.let { parameters.append("rt", it) }
                            final?.let { parameters.append("final", if (it) "1" else "0") }
                            format?.let { parameters.append("fmt", it.toString()) }
                            if (playlistId != null) {
                                parameters.append("list", playlistId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun validatedStatsUrl(value: String): Url {
        val url = Url(value)
        require(
            url.protocol.name == "https" &&
                url.port == 443 &&
                url.host in STATS_HOSTS &&
                url.encodedPath in STATS_PATHS &&
                !url.hasUserInfo(),
        ) {
            "Playback statistics URL must use an approved YouTube HTTPS endpoint"
        }
        return url
    }

    private fun validatedMediaUrl(value: String): Url {
        val url = Url(value)
        require(
            url.protocol.name == "https" &&
                url.port == 443 &&
                (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) &&
                url.encodedPath == "/videoplayback" &&
                !url.hasUserInfo(),
        ) {
            "Media URL must use an approved Google Video HTTPS endpoint"
        }
        return url
    }

    suspend fun search(
        client: TubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean? = null,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = setLogin ?: true
        val visitorDataForRequest = requestSession.visitorData.takeUnless { requestSession.regionOverrideActive }
        return executeRequest(
            operation = "search",
            requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported },
        ) {
            postClient.postBounded("search", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    visitorData = visitorDataForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    SearchBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                visitorDataForRequest,
                                if (setLoginForRequest) requestSession.dataSyncId else null,
                            ),
                        query = query,
                        params = params,
                    ),
                )
                if (continuation != null) {
                    parameter("continuation", continuation)
                    parameter("ctoken", continuation)
                }
            }
        }
    }

    suspend fun getSearchSuggestions(
        client: TubeClient,
        input: String,
        setLogin: Boolean = false,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeRequest(
            operation = "getSearchSuggestions",
            requestSession = requestSession.takeIf { setLogin && client.loginSupported },
        ) {
            postClient.postBounded("music/get_search_suggestions", keepErrorBody = false) {
                ytClient(client, requestSession, setLogin = setLogin, includeAccountCookies = setLogin)
                setBody(
                    GetSearchSuggestionsBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                requestSession.visitorData,
                                if (setLogin) requestSession.dataSyncId else null,
                            ),
                        input = input,
                    ),
                )
            }
        }
    }

    suspend fun feedback(
        client: TubeClient,
        tokens: List<String>,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeMutation("feedback", requestSession) { session ->
            postClient.postBounded("feedback") {
                ytClient(client, session, setLogin = true)
                setBody(
                    FeedbackBody(
                        context = client.toContext(session.locale, session.visitorData, session.dataSyncId),
                        feedbackTokens = tokens,
                    ),
                )
            }
        }
    }

    suspend fun getQueue(
        client: TubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = requestSession.useLoginForBrowse
        return executeRequest(
            operation = "getQueue",
            requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported },
        ) {
            postClient.postBounded("music/get_queue", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    GetQueueBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                requestSession.visitorData,
                                requestSession.dataSyncId.takeIf { setLoginForRequest },
                            ),
                        videoIds = videoIds,
                        playlistId = playlistId,
                    ),
                )
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal suspend fun getTranscriptText(
        client: TubeClient,
        videoId: String,
        maxBytes: Int,
    ): String {
        val requestSession = sessionSnapshot()
        return executeStreamingRequest(
            operation = "getTranscript",
            prepare = {
                postClient.preparePost("https://music.youtube.com/youtubei/v1/get_transcript") {
                    expectSuccess = false

                    parameter("key", TRANSCRIPT_API_KEY)
                    headers {
                        append("Content-Type", "application/json")
                    }
                    setBody(
                        GetTranscriptBody(
                            context = client.toContext(requestSession.locale, null, null),
                            params =
                                Base64.encode(
                                    "\n${11.toChar()}$videoId".encodeToByteArray(),
                                ),
                        ),
                    )
                }
            },
            onResponse = { response -> response.bodyAsTextLimited(maxBytes) },
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun getTranscript(
        client: TubeClient,
        videoId: String,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeRequest(operation = "getTranscript") {
            postClient.postBounded("https://music.youtube.com/youtubei/v1/get_transcript", keepErrorBody = false) {

                parameter("key", TRANSCRIPT_API_KEY)
                headers {
                    append("Content-Type", "application/json")
                }
                setBody(
                    GetTranscriptBody(
                        context = client.toContext(requestSession.locale, null, null),
                        params =
                            Base64.encode(
                                "\n${11.toChar()}$videoId".encodeToByteArray(),
                            ),
                    ),
                )
            }
        }
    }

    suspend fun accountMenu(client: TubeClient): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeRequest(operation = "accountMenu", requestSession = requestSession, requireSuccess = false) {
            postClient.postBounded("account/account_menu") {
                ytClient(client, requestSession, setLogin = true)
                setBody(
                    AccountMenuBody(
                        client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    ),
                )
            }
        }
    }

    suspend fun accountsList(client: TubeClient): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeRequest(operation = "accountsList", requestSession = requestSession, requireSuccess = false) {
            postClient.postBounded("account/accounts_list") {
                ytClient(client, requestSession, setLogin = true)
                setBody(
                    AccountsListBody(
                        context = client.toContext(requestSession.locale, requestSession.visitorData, null),
                        requestType = "ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER",
                        callCircumstance = "SWITCHING_USERS_FULL",
                    ),
                )
            }
        }
    }

    suspend fun uploadSong(
        fileName: String,
        bytes: ByteArray,
    ): HttpResponse = uploadSong(fileName, bytes.size.toLong()) { ByteReadChannel(bytes) }

    suspend fun uploadSong(
        fileName: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)?,
    ): HttpResponse = uploadSong(fileName, bytes.size.toLong(), onProgress) { ByteReadChannel(bytes) }

    suspend fun uploadSong(
        fileName: String,
        contentLength: Long,
        content: () -> ByteReadChannel,
    ): HttpResponse = uploadSong(fileName, contentLength, null, content)

    private suspend fun uploadSong(
        fileName: String,
        contentLength: Long,
        onProgress: ((Float) -> Unit)?,
        content: () -> ByteReadChannel,
    ): HttpResponse {
        require(contentLength > 0L) { "Upload content must not be empty" }
        require(contentLength < MAX_UPLOAD_BYTES) { "Upload content must be smaller than 300 MiB" }
        val requestSession = sessionSnapshot()
        return withSessionBoundRequest(requestSession) {
            val startResponse =
                executeRequest(operation = "uploadSong:start", retryTransientFailures = false, requireSuccess = false) {
                    postClient.postBounded(
                        "https://upload.youtube.com/upload/usermusic/http?authuser=${requestSession.authUser}",
                        MAX_UPLOAD_START_RESPONSE_BYTES,
                    ) {
                        uploadAuthHeaders(requestSession)
                        header("X-Goog-Upload-Command", "start")
                        header("X-Goog-Upload-Header-Content-Length", contentLength.toString())
                        header("X-Goog-Upload-Protocol", "resumable")
                        setBody(
                            FormDataContent(
                                Parameters.build {
                                    append("filename", fileName)
                                },
                            ),
                        )
                    }
                }
            val uploadUrl = startResponse.headers["X-Goog-Upload-URL"]
            startResponse.bodyAsTextLimited(MAX_UPLOAD_START_RESPONSE_BYTES)
            check(startResponse.status.isSuccess()) { "Upload session start failed with HTTP ${startResponse.status.value}" }
            val validatedUploadUrl = validatedUploadUrl(checkNotNull(uploadUrl) { "Missing upload session URL" })
            executeRequest(operation = "uploadSong:finalize", retryTransientFailures = false, requireSuccess = false) {
                postClient.postBounded(validatedUploadUrl.toString(), MAX_UPLOAD_START_RESPONSE_BYTES) {
                    timeout {
                        requestTimeoutMillis = 600_000L
                        socketTimeoutMillis = 600_000L
                    }
                    uploadAuthHeaders(requestSession)
                    header("X-Goog-Upload-Command", "upload, finalize")
                    header("X-Goog-Upload-Offset", "0")
                    onUpload { bytesSentTotal, requestContentLength ->
                        val total = requestContentLength ?: contentLength
                        if (total > 0L) onProgress?.invoke(bytesSentTotal.toFloat() / total.toFloat())
                    }
                    setBody(
                        object : OutgoingContent.ReadChannelContent() {
                            override val contentLength: Long = contentLength
                            override val contentType: ContentType = ContentType.Application.FormUrlEncoded

                            override fun readFrom(): ByteReadChannel = content()
                        },
                    )
                }
            }
        }
    }

    private fun validatedUploadUrl(value: String): Url {
        val url = Url(value)
        require(
            url.protocol.name == "https" &&
                url.port == 443 &&
                url.host == "upload.youtube.com" &&
                (url.encodedPath == "/" || url.encodedPath.startsWith("/upload/")) &&
                !url.hasUserInfo(),
        ) {
            "Upload session URL must use the approved YouTube HTTPS endpoint"
        }
        return url
    }

    private fun Url.hasUserInfo(): Boolean = user != null || password != null

    suspend fun likeVideo(
        client: TubeClient,
        videoId: String,
    ) = likeVideo(client, videoId, sessionSnapshot())

    suspend fun likeVideo(
        client: TubeClient,
        videoId: String,
        requestSession: TubeSession,
    ) = executeMutation("likeVideo", requestSession) { requestSession ->
        postClient.postBounded("like/like") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.video(videoId),
                ),
            )
        }
    }

    suspend fun unlikeVideo(
        client: TubeClient,
        videoId: String,
    ) = unlikeVideo(client, videoId, sessionSnapshot())

    suspend fun unlikeVideo(
        client: TubeClient,
        videoId: String,
        requestSession: TubeSession,
    ) = executeMutation("unlikeVideo", requestSession) { requestSession ->
        postClient.postBounded("like/removelike") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.video(videoId),
                ),
            )
        }
    }

    suspend fun dislikeVideo(
        client: TubeClient,
        videoId: String,
    ) = dislikeVideo(client, videoId, sessionSnapshot())

    suspend fun dislikeVideo(
        client: TubeClient,
        videoId: String,
        requestSession: TubeSession,
    ) = executeMutation("dislikeVideo", requestSession) { requestSession ->
        postClient.postBounded("like/dislike") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.video(videoId),
                ),
            )
        }
    }

    suspend fun subscribeChannel(
        client: TubeClient,
        channelId: String,
        params: String? = null,
    ) = executeMutation("subscribeChannel") { requestSession ->
        postClient.postBounded("subscription/subscribe") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                SubscribeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    listOf(channelId),
                    params,
                ),
            )
        }
    }

    suspend fun unsubscribeChannel(
        client: TubeClient,
        channelId: String,
        params: String? = null,
    ) = executeMutation("unsubscribeChannel") { requestSession ->
        postClient.postBounded("subscription/unsubscribe") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                SubscribeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    listOf(channelId),
                    params,
                ),
            )
        }
    }

    suspend fun likePlaylist(
        client: TubeClient,
        playlistId: String,
    ) = executeMutation("likePlaylist") { requestSession ->
        postClient.postBounded("like/like") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.playlist(playlistId),
                ),
            )
        }
    }

    suspend fun unlikePlaylist(
        client: TubeClient,
        playlistId: String,
    ) = unlikePlaylist(client, playlistId, sessionSnapshot())

    suspend fun unlikePlaylist(
        client: TubeClient,
        playlistId: String,
        requestSession: TubeSession,
    ) = executeMutation("unlikePlaylist", requestSession) { requestSession ->
        postClient.postBounded("like/removelike") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.playlist(playlistId),
                ),
            )
        }
    }

    suspend fun addToPlaylist(
        client: TubeClient,
        playlistId: String,
        videoId: String,
    ) = executeMutation("addToPlaylist") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.addVideoAction(addedVideoId = videoId)),
                ),
            )
        }
    }

    suspend fun addPlaylistToPlaylist(
        client: TubeClient,
        playlistId: String,
        addedPlaylistId: String,
    ) = executeMutation("addPlaylistToPlaylist") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.addPlaylistAction(addedPlaylistId.removePrefix("VL"))),
                ),
            )
        }
    }

    suspend fun movePlaylistSong(
        client: TubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = executeMutation("movePlaylistSong") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.moveVideoAction(setVideoId = setVideoId, movedSetVideoIdSuccessor = successorSetVideoId)),
                ),
            )
        }
    }

    suspend fun removePlaylistSong(
        client: TubeClient,
        playlistId: String,
        setVideoId: String,
        videoId: String,
    ) = executeMutation("removePlaylistSong") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.removeVideoAction(setVideoId = setVideoId, removedVideoId = videoId)),
                ),
            )
        }
    }

    suspend fun removePlaylistSongs(
        client: TubeClient,
        playlistId: String,
        songs: List<Pair<String, String>>,
    ) = executeMutation("removePlaylistSongs") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    songs.map { (setVideoId, videoId) -> Action.removeVideoAction(setVideoId, videoId) },
                ),
            )
        }
    }

    suspend fun createPlaylist(
        client: TubeClient,
        title: String,
    ) = executeMutation("createPlaylist") { requestSession ->
        postClient.postBounded("playlist/create") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                CreatePlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    title,
                    "PRIVATE",
                ),
            )
        }
    }

    suspend fun renamePlaylist(
        client: TubeClient,
        playlistId: String,
        title: String,
    ) = executeMutation("renamePlaylist") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.renamePlaylistAction(title)),
                ),
            )
        }
    }

    suspend fun setPlaylistDescription(
        client: TubeClient,
        playlistId: String,
        description: String,
    ) = executeMutation("setPlaylistDescription") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.setPlaylistDescriptionAction(description)),
                ),
            )
        }
    }

    suspend fun setPlaylistThumbnail(
        client: TubeClient,
        playlistId: String,
        image: ByteArray,
    ): HttpResponse {
        require(image.isNotEmpty()) { "Playlist thumbnail must not be empty" }
        val requestSession = sessionSnapshot()
        val startResponse =
            executeMutation("setPlaylistThumbnail:start", requestSession) { session ->
                postClient.postBounded(
                    "https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail",
                    MAX_IMAGE_UPLOAD_RESPONSE_BYTES,
                ) {
                    uploadAuthHeaders(session)
                    header("X-Goog-Upload-Command", "start")
                    header("X-Goog-Upload-Protocol", "resumable")
                    header("X-Goog-Upload-Header-Content-Length", image.size.toString())
                }
            }
        val uploadId = startResponse.headers["X-Goog-Upload-Id"] ?: startResponse.headers["X-Guploader-Uploadid"]
        startResponse.bodyAsTextLimited(MAX_UPLOAD_START_RESPONSE_BYTES)
        check(startResponse.status.isSuccess()) {
            "Playlist thumbnail upload session start failed with HTTP ${startResponse.status.value}"
        }
        check(!uploadId.isNullOrBlank()) { "Missing playlist thumbnail upload ID" }

        val uploadResponse =
            executeMutation("setPlaylistThumbnail:upload", requestSession) { session ->
                postClient.postBounded(
                    "https://music.youtube.com/playlist_image_upload/playlist_custom_thumbnail",
                    MAX_IMAGE_UPLOAD_RESPONSE_BYTES,
                ) {
                    uploadAuthHeaders(session)
                    parameter("upload_id", uploadId)
                    parameter("upload_protocol", "resumable")
                    header("X-Goog-Upload-Command", "upload, finalize")
                    header("X-Goog-Upload-Offset", "0")
                    setBody(image)
                }
            }
        val uploadBody = uploadResponse.bodyAsTextLimited(MAX_IMAGE_UPLOAD_RESPONSE_BYTES)
        check(uploadResponse.status.isSuccess()) {
            "Playlist thumbnail upload failed with HTTP ${uploadResponse.status.value}"
        }
        val encryptedBlobId = IMAGE_UPLOAD_JSON.decodeFromString<ImageUploadResponse>(uploadBody).encryptedBlobId

        return executeMutation("setPlaylistThumbnail:apply", requestSession) { session ->
            postClient.postBounded("browse/edit_playlist") {
                ytClient(client, session, setLogin = true)
                setBody(
                    EditPlaylistBody(
                        client.toContext(session.locale, session.visitorData, session.dataSyncId),
                        playlistId.removePrefix("VL"),
                        listOf(Action.setCustomThumbnailAction(encryptedBlobId)),
                    ),
                )
            }
        }
    }

    suspend fun removePlaylistThumbnail(
        client: TubeClient,
        playlistId: String,
    ) = executeMutation("removePlaylistThumbnail") { requestSession ->
        postClient.postBounded("browse/edit_playlist") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                EditPlaylistBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    playlistId.removePrefix("VL"),
                    listOf(Action.removeCustomThumbnailAction()),
                ),
            )
        }
    }

    suspend fun deletePlaylist(
        client: TubeClient,
        playlistId: String,
    ) = deletePlaylist(client, playlistId, sessionSnapshot())

    suspend fun deletePlaylist(
        client: TubeClient,
        playlistId: String,
        requestSession: TubeSession,
    ) = executeMutation("deletePlaylist", requestSession) { requestSession ->
        postClient.postBounded("playlist/delete") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                PlaylistDeleteBody(
                    context =
                        client.toContext(
                            requestSession.locale,
                            requestSession.visitorData,
                            requestSession.dataSyncId,
                        ),
                    playlistId = playlistId.removePrefix("VL"),
                ),
            )
        }
    }

    suspend fun deletePrivatelyOwnedEntity(
        client: TubeClient,
        entityId: String,
    ) = executeMutation("deletePrivatelyOwnedEntity") { requestSession ->
        postClient.postBounded("music/delete_privately_owned_entity") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                DeletePrivatelyOwnedEntityBody(
                    context = client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    entityId = entityId,
                ),
            )
        }
    }

    suspend fun fetchFreshVisitorData(): String? = fetchFreshVisitorData(sessionSnapshot(), forceRefresh = true)

    suspend fun fetchFreshVisitorData(
        requestSession: TubeSession,
        forceRefresh: Boolean = false,
    ): String? {
        val deferred =
            visitorDataFetchMutex.withLock {
                visitorDataScope.coroutineContext.ensureActive()
                val currentSession = session.load()
                if (!forceRefresh || requestSession != currentSession) {

                    // visitor a concurrent flight just published, which bumped the generation.
                    currentSession.visitorData
                        ?.takeIf { visitorData -> visitorData.isNotBlank() && sessionIsCompatible(requestSession, currentSession) }
                        ?.let { visitorData ->
                            return@withLock CompletableDeferred<String?>().also { it.complete(visitorData) }
                        }
                }

                val sessionIdentity = requestSession.copy(visitorData = null)

                visitorDataFailure
                    ?.takeIf { !forceRefresh && it.first == sessionIdentity && it.second.elapsedNow() < VISITOR_FAILURE_COOLDOWN }
                    ?.let { return@withLock CompletableDeferred<String?>().also { it.complete(null) } }
                visitorDataFlight
                    ?.takeIf { it.sessionIdentity == sessionIdentity && !it.result.isCompleted }
                    ?.result
                    ?: CompletableDeferred<String?>().also { result ->
                        visitorDataFlight = VisitorDataFlight(sessionIdentity, result)
                        visitorDataScope
                            .launch {
                                val outcome =
                                    runCatching { withTimeout(VISITOR_FETCH_TIMEOUT_MS) { fetchFreshVisitorDataNetwork(requestSession) } }

                                withContext(NonCancellable) {
                                    visitorDataFetchMutex.withLock {
                                        if (visitorDataFlight?.result === result) {
                                            visitorDataFlight = null
                                            outcome.onSuccess { visitorData ->
                                                visitorDataFailure =
                                                    sessionIdentity
                                                        .takeIf { visitorData.isNullOrBlank() }
                                                        ?.let { it to TimeSource.Monotonic.markNow() }
                                            }
                                        }
                                    }
                                }
                                outcome.fold(result::complete, result::completeExceptionally)
                            }.invokeOnCompletion { cause ->
                                if (cause != null) result.completeExceptionally(cause)
                            }
                    }
            }
        return deferred.await()
    }

    private suspend fun fetchFreshVisitorDataNetwork(requestSession: TubeSession): String? =
        try {
            val startTime = Clock.System.now().toEpochMilliseconds()
            logger.d(TAG, "fetchFreshVisitorData start")

            val serviceWorkerData =
                withTimeoutOrNull(SERVICE_WORKER_VISITOR_TIMEOUT_MS) {
                    try {
                        executeStreamingRequest(
                            operation = "fetchFreshVisitorData",
                            retryTransientFailures = false,
                            requireSuccess = false,
                            prepare = {
                                httpClient.prepareGet(VISITOR_DATA_URL) {
                                    expectSuccess = false
                                    header("User-Agent", TubeClient.USER_AGENT_WEB)
                                    header(HttpHeaders.Accept, "application/json,text/plain,*/*")
                                    header(
                                        HttpHeaders.AcceptLanguage,
                                        requestSession.locale.acceptLanguageHeader(),
                                    )
                                }
                            },
                            onResponse = { response ->
                                if (response.status.value in 200..299) {
                                    sanitizeVisitorData(
                                        parseServiceWorkerVisitorData(response.bodyAsTextLimited(MAX_VISITOR_RESPONSE_BYTES)),
                                    )
                                } else {
                                    response.bodyAsChannel().cancel(null)
                                    null
                                }
                            },
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.d(TAG, "service-worker visitor data unavailable (${error.logType()})")
                        null
                    }
                }
            val data =
                serviceWorkerData
                    ?: sanitizeVisitorData(fetchHomepageVisitorData(requestSession))
            if (!data.isNullOrBlank()) {
                publishSession(requestSession, requestSession.copy(visitorData = data))
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            logger.d(
                TAG,
                "fetchFreshVisitorData success hasVisitorData=${!data.isNullOrBlank()} source=${if (serviceWorkerData != null) "service-worker" else "homepage"} elapsed=${elapsed}ms",
            )
            data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "fetchFreshVisitorData failed (${e.logType()})")
            null
        }

    private suspend fun fetchHomepageVisitorData(requestSession: TubeSession): String? =
        executeStreamingRequest(
            operation = "fetchFreshVisitorData",
            requireSuccess = false,
            maxAttempts = 2,
            prepare = {
                httpClient.prepareGet(ORIGIN_MUSIC) {
                    expectSuccess = false
                    header("User-Agent", TubeClient.USER_AGENT_WEB)
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header(
                        HttpHeaders.AcceptLanguage,
                        requestSession.locale.acceptLanguageHeader(),
                    )
                }
            },
            onResponse = { response ->
                if (response.status.value !in 200..299) {
                    response.bodyAsChannel().cancel(null)
                    return@executeStreamingRequest null
                }
                if ((response.contentLength() ?: 0L) > MAX_VISITOR_RESPONSE_BYTES) {
                    response.bodyAsChannel().cancel(null)
                    throw ResponseTooLargeException(MAX_VISITOR_RESPONSE_BYTES)
                }
                var visitor: String? = null
                val html =
                    response.bodyAsTextLimited(MAX_VISITOR_RESPONSE_BYTES) { prefix ->
                        visitor = VISITOR_DATA_REGEX.find(prefix)?.groupValues?.get(1)
                        visitor != null
                    }
                visitor ?: VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1)
            },
        )

    private fun parseServiceWorkerVisitorData(responseText: String): String? =
        runCatching {
            Json
                .parseToJsonElement(responseText.substringAfter('\n').trimStart())
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray[0]
                .jsonArray[0]
                .jsonArray[13]
                .jsonPrimitive
                .contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

class TubeHttpException(
    val operation: String,
    val status: HttpStatusCode,
) : IllegalStateException("$operation failed with HTTP ${status.value}")

private fun Throwable.logType(): String = this::class.simpleName ?: "Exception"

private fun injectPrefCookie(
    cookie: String?,
    hl: String,
    gl: String,
): String {
    if (cookie.isNullOrBlank()) return "PREF=hl=$hl&gl=$gl"
    val parts = cookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    val prefParts = mutableListOf<String>()
    val otherParts = mutableListOf<String>()
    var foundPref = false
    for (part in parts) {
        if (part.startsWith("PREF=", ignoreCase = true)) {
            foundPref = true
            val prefValue = part.substringAfter("=")
            prefValue.split("&").filterTo(prefParts) { !it.startsWith("hl=") && !it.startsWith("gl=") }
        } else {
            otherParts.add(part)
        }
    }
    prefParts.add(0, "hl=$hl")
    prefParts.add(1, "gl=$gl")
    val newPref = "PREF=${prefParts.joinToString("&")}"
    return (otherParts + newPref).joinToString("; ")
}

private const val PLAYER_FORMAT_FIELDS =
    "itag,url,mimeType,bitrate,averageBitrate,width,height,contentLength,quality,audioQuality," +
        "approxDurationMs,audioSampleRate,audioChannels,loudnessDb,lastModified,xtags,audioTrack,isDrc,signatureCipher," +
        "drmFamilies,drmTrackType"

internal const val PLAYER_RESPONSE_FIELD_MASK =
    "playabilityStatus(status,reason,desktopLegacyAgeGateReason)," +
        "streamingData(formats($PLAYER_FORMAT_FIELDS),adaptiveFormats($PLAYER_FORMAT_FIELDS)," +
        "expiresInSeconds,hlsManifestUrl,dashManifestUrl,serverAbrStreamingUrl,drmParams,licenseInfos)," +
        "videoDetails(videoId,title,author,channelId,lengthSeconds,musicVideoType,viewCount,thumbnail,isLiveContent)," +
        "playbackTracking(videostatsPlaybackUrl,videostatsWatchtimeUrl,atrUrl," +
        "videostatsScheduledFlushWalltimeSeconds,videostatsDefaultFlushIntervalSeconds)," +
        "playerConfig(audioConfig(loudnessDb,perceptualLoudnessDb)," +
        "mediaCommonConfig(mediaUstreamerRequestConfig(videoPlaybackUstreamerConfig)))"

internal const val PLAYER_RESPONSE_TV_FIELD_MASK =
    "$PLAYER_RESPONSE_FIELD_MASK," +
        "adPlacements(adPlacementRenderer(config(adPlacementConfig(kind))," +
        "renderer(instreamVideoAdRenderer(playerVars,skipOffsetMilliseconds))))"

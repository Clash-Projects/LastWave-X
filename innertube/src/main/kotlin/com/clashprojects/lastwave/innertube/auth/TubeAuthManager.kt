package com.clashprojects.lastwave.innertube.auth

import com.clashprojects.lastwave.innertube.TubeHttpException
import com.clashprojects.lastwave.innertube.WaveTube
import com.clashprojects.lastwave.innertube.bodyAsTextLimited
import com.clashprojects.lastwave.innertube.extract.ExtractedStream
import com.clashprojects.lastwave.innertube.model.AccountInfo
import com.clashprojects.lastwave.innertube.model.TubeClient
import com.clashprojects.lastwave.innertube.model.response.AccountMenuResponse
import com.clashprojects.lastwave.innertube.model.response.AccountsListResponse
import com.clashprojects.lastwave.innertube.net.TubeHttpFactory
import com.clashprojects.lastwave.innertube.util.parseCookieString
import com.clashprojects.lastwave.innertube.util.sanitizeCookieString
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

data class StoredTubeCredentials(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "0",
    val accountInfo: AccountInfo? = null,
    val isPremium: Boolean? = null,
    val premiumCheckedAtMs: Long = 0L,
) {
    val isSignedIn: Boolean
        get() = hasAuthCookie(cookie)

    override fun toString(): String =
        "StoredTubeCredentials(signedIn=$isSignedIn, visitorData=${visitorData.isNotBlank()}, authUser=$authUser, isPremium=$isPremium)"
}

interface TubeCredentialStore {
    suspend fun read(): StoredTubeCredentials

    suspend fun write(credentials: StoredTubeCredentials)

    suspend fun clear()
}

class InMemoryTubeCredentialStore(
    initial: StoredTubeCredentials = StoredTubeCredentials(),
) : TubeCredentialStore {
    private val state = MutableStateFlow(initial)

    override suspend fun read(): StoredTubeCredentials = state.value

    override suspend fun write(credentials: StoredTubeCredentials) {
        state.value = credentials
    }

    override suspend fun clear() {
        state.value = StoredTubeCredentials()
    }
}

private val AUTH_COOKIE_KEYS = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

fun extractAuthCredential(cookie: String): String? {
    if (cookie.isBlank()) return null
    val parsed = runCatching { parseCookieString(cookie) }.getOrNull() ?: return null
    return AUTH_COOKIE_KEYS.firstNotNullOfOrNull { key -> parsed[key]?.takeIf(String::isNotEmpty) }
}

fun hasAuthCookie(cookie: String): Boolean = extractAuthCredential(cookie) != null

fun isSessionExpiredStatus(
    statusCode: Int,
    authenticated: Boolean,
    treatForbiddenAsExpired: Boolean = false,
): Boolean = authenticated && (statusCode == 401 || (treatForbiddenAsExpired && statusCode == 403))

class TubeAuthManager(
    private val tube: WaveTube,
    private val store: TubeCredentialStore = InMemoryTubeCredentialStore(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var premiumProbeJob: Job? = null
    private val consecutiveStandardAuthStreams = AtomicInteger(0)

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    val isSignedIn: Boolean
        get() = tube.hasSapCookieAuth()

    fun dismissExpiredNotice() {
        _sessionExpired.value = false
    }

    fun notifyExpiredIfMatching(expected: WaveTube.TubeSession): Boolean {
        val cleared = tube.clearSessionIfMatches(expected)
        if (cleared) {
            _sessionExpired.value = true
            _isPremium.value = false
            consecutiveStandardAuthStreams.set(0)
        }
        return cleared
    }

    suspend fun restoreSession(): StoredTubeCredentials {
        val saved = store.read()
        applyToTube(saved)
        _isPremium.value = saved.isSignedIn && saved.isPremium == true
        consecutiveStandardAuthStreams.set(0)

        var current = saved
        if (saved.visitorData.isBlank()) {
            val freshVisitor =
                try {
                    tube.fetchFreshVisitorData()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (!freshVisitor.isNullOrBlank()) {
                current = saved.copy(visitorData = freshVisitor)
                store.write(current)
                applyToTube(current)
            }
        }

        if (current.isSignedIn && isPremiumCheckStale(current)) {
            triggerPremiumProbeAsync()
        }
        return current
    }

    suspend fun saveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String = "",
        authUser: String = "0",
        accountInfo: AccountInfo? = null,
        detectedPremium: Boolean? = null,
    ): StoredTubeCredentials {
        val cleanCookie = sanitizeCookieString(cookie)
        val cleanAuthUser = authUser.filter(Char::isDigit).ifBlank { "0" }
        val checkedAt = if (detectedPremium != null) nowMs() else 0L
        val creds =
            StoredTubeCredentials(
                cookie = cleanCookie,
                visitorData = visitorData.trim(),
                dataSyncId = dataSyncId.trim(),
                authUser = cleanAuthUser,
                accountInfo = accountInfo,
                isPremium = detectedPremium,
                premiumCheckedAtMs = checkedAt,
            )
        store.write(creds)
        applyToTube(creds)
        _sessionExpired.value = false
        _isPremium.value = creds.isSignedIn && detectedPremium == true
        consecutiveStandardAuthStreams.set(0)
        if (creds.isSignedIn && detectedPremium == null) {
            triggerPremiumProbeAsync()
        }
        return creds
    }

    suspend fun fetchCandidateAccounts(
        cookie: String,
        visitorData: String,
        dataSyncId: String = "",
        authUser: String = "0",
    ): List<AccountsListResponse.Account> {
        val isolated = isolatedCandidate(cookie, visitorData, dataSyncId, authUser)
        return try {
            val response = isolated.accountsList(TubeClient.WEB_REMIX)
            if (!response.status.isSuccess()) return emptyList()
            val body = response.bodyAsTextLimited(MAX_AUTH_RESPONSE_BYTES)
            TubeHttpFactory.defaultJson.decodeFromString<AccountsListResponse>(body).extractAccounts()
        } finally {
            isolated.close()
        }
    }

    suspend fun verifyAndSave(
        cookie: String,
        visitorData: String,
        dataSyncId: String = "",
        authUser: String = "0",
    ): AccountInfo? {
        val isolated = isolatedCandidate(cookie, visitorData, dataSyncId, authUser)
        val (info, detectedPremium) =
            try {
                val response = isolated.accountMenu(TubeClient.WEB_REMIX)
                if (!response.status.isSuccess()) return null
                val body = response.bodyAsTextLimited(MAX_AUTH_RESPONSE_BYTES)
                val parsed =
                    TubeHttpFactory.defaultJson
                        .decodeFromString<AccountMenuResponse>(body)
                        .extractAccountInfo()
                parsed to PremiumDetector.detectFromJson(body)
            } finally {
                isolated.close()
            }
        if (info == null) return null
        saveCredentials(cookie, visitorData, dataSyncId, authUser, info, detectedPremium)
        return info
    }

    suspend fun refreshAccountInfo(): AccountInfo? {
        val snapshot = tube.sessionSnapshot()
        if (!tube.hasSapCookieAuth(snapshot)) return null
        return try {
            val response = tube.accountMenu(TubeClient.WEB_REMIX)
            if (isSessionExpiredStatus(response.status.value, authenticated = true)) {
                notifyExpiredIfMatching(snapshot)
                return null
            }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsTextLimited(MAX_AUTH_RESPONSE_BYTES)
            observeRawResponse(body)
            val info =
                TubeHttpFactory.defaultJson
                    .decodeFromString<AccountMenuResponse>(body)
                    .extractAccountInfo()
                    ?: return null
            val current = store.read()
            store.write(current.copy(accountInfo = info))
            info
        } catch (e: TubeHttpException) {
            if (isSessionExpiredStatus(e.status.value, authenticated = true)) {
                notifyExpiredIfMatching(snapshot)
            }
            null
        }
    }

    fun observeRawResponse(rawJson: String) {
        if (!isSignedIn) return
        val detected = PremiumDetector.detectFromJson(rawJson) ?: return
        publishPremiumStatus(detected)
    }

    fun observeResolvedStream(stream: ExtractedStream) {
        if (!isSignedIn) return
        if (PremiumDetector.isPremiumStream(stream)) {
            consecutiveStandardAuthStreams.set(0)
            if (!_isPremium.value) publishPremiumStatus(true)
            return
        }
        if (_isPremium.value && PremiumDetector.isAuthenticatedPremiumClient(stream.clientName)) {
            if (consecutiveStandardAuthStreams.incrementAndGet() >= DOWNGRADE_THRESHOLD) {
                consecutiveStandardAuthStreams.set(0)
                publishPremiumStatus(false)
            }
        }
    }

    fun triggerPremiumProbeAsync() {
        val snapshot = tube.sessionSnapshot()
        if (!tube.hasSapCookieAuth(snapshot)) return
        if (premiumProbeJob?.isActive == true) return
        premiumProbeJob =
            backgroundScope.launch {
                val detected =
                    withTimeoutOrNull(PREMIUM_PROBE_TIMEOUT_MS) {
                        runCatching {
                            val response = tube.browse(TubeClient.WEB_REMIX, browseId = "FEmusic_home", setLogin = true)
                            if (!response.status.isSuccess()) return@runCatching null
                            val body = response.bodyAsTextLimited(MAX_PROBE_RESPONSE_BYTES)
                            PremiumDetector.detectFromJson(body)
                        }.getOrNull()
                    }
                if (detected != null && tube.sessionSnapshot().generation == snapshot.generation) {
                    publishPremiumStatus(detected)
                }
            }
    }

    suspend fun signOut() {
        premiumProbeJob?.cancel()
        premiumProbeJob = null
        consecutiveStandardAuthStreams.set(0)
        _isPremium.value = false
        store.clear()
        tube.replaceSession(
            cookie = null,
            visitorData = null,
            dataSyncId = null,
            authUser = "0",
            useLoginForBrowse = false,
        )
        _sessionExpired.value = false
    }

    fun close() {
        backgroundScope.cancel()
    }

    private fun publishPremiumStatus(premium: Boolean) {
        _isPremium.value = premium
        val checkedAt = nowMs()
        backgroundScope.launch {
            runCatching {
                val current = store.read()
                if (current.isSignedIn && (current.isPremium != premium || checkedAt - current.premiumCheckedAtMs > PREMIUM_CACHE_TTL_MS)) {
                    store.write(current.copy(isPremium = premium, premiumCheckedAtMs = checkedAt))
                }
            }
        }
    }

    private fun isPremiumCheckStale(creds: StoredTubeCredentials): Boolean =
        creds.isPremium == null || (nowMs() - creds.premiumCheckedAtMs) >= PREMIUM_CACHE_TTL_MS

    private fun isolatedCandidate(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): WaveTube =
        tube.createIsolatedSession(includeAccount = false).apply {
            replaceSession(
                cookie = sanitizeCookieString(cookie),
                visitorData = visitorData.ifBlank { null },
                dataSyncId = dataSyncId.ifBlank { null },
                authUser = authUser.filter(Char::isDigit).ifBlank { "0" },
                useLoginForBrowse = true,
            )
        }

    private fun applyToTube(creds: StoredTubeCredentials) {
        tube.replaceSession(
            cookie = creds.cookie.ifBlank { null },
            visitorData = creds.visitorData.ifBlank { null },
            dataSyncId = creds.dataSyncId.ifBlank { null },
            authUser = creds.authUser,
            useLoginForBrowse = creds.isSignedIn,
        )
    }

    private companion object {
        private const val MAX_AUTH_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_PROBE_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val PREMIUM_PROBE_TIMEOUT_MS = 4_000L
        private const val PREMIUM_CACHE_TTL_MS = 24L * 60 * 60 * 1000L
        private const val DOWNGRADE_THRESHOLD = 3
    }
}

private fun AccountMenuResponse.extractAccountInfo(): AccountInfo? =
    actions.firstNotNullOfOrNull { action ->
        val popup = action.openPopupAction?.popup
        val renderer = popup?.multiPageMenuRenderer ?: popup?.popupContent?.multiPageMenuRenderer
        renderer?.header?.activeAccountHeaderRenderer?.toAccountInfo()
    }

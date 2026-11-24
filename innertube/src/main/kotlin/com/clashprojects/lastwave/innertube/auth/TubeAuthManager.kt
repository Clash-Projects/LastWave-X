package com.clashprojects.lastwave.innertube.auth

import com.clashprojects.lastwave.innertube.TubeHttpException
import com.clashprojects.lastwave.innertube.WaveTube
import com.clashprojects.lastwave.innertube.bodyAsTextLimited
import com.clashprojects.lastwave.innertube.model.AccountInfo
import com.clashprojects.lastwave.innertube.model.TubeClient
import com.clashprojects.lastwave.innertube.model.response.AccountMenuResponse
import com.clashprojects.lastwave.innertube.model.response.AccountsListResponse
import com.clashprojects.lastwave.innertube.net.TubeHttpFactory
import com.clashprojects.lastwave.innertube.util.parseCookieString
import com.clashprojects.lastwave.innertube.util.sanitizeCookieString
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredTubeCredentials(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val authUser: String = "0",
    val accountInfo: AccountInfo? = null,
) {
    val isSignedIn: Boolean
        get() = hasAuthCookie(cookie)

    override fun toString(): String =
        "StoredTubeCredentials(signedIn=$isSignedIn, visitorData=${visitorData.isNotBlank()}, authUser=$authUser)"
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
) {
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    val isSignedIn: Boolean
        get() = tube.hasSapCookieAuth()

    fun dismissExpiredNotice() {
        _sessionExpired.value = false
    }

    fun notifyExpiredIfMatching(expected: WaveTube.TubeSession): Boolean {
        val cleared = tube.clearSessionIfMatches(expected)
        if (cleared) _sessionExpired.value = true
        return cleared
    }

    suspend fun restoreSession(): StoredTubeCredentials {
        val saved = store.read()
        applyToTube(saved)
        if (saved.visitorData.isNotBlank()) return saved

        val freshVisitor =
            try {
                tube.fetchFreshVisitorData()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        if (freshVisitor.isNullOrBlank()) return saved
        val updated = saved.copy(visitorData = freshVisitor)
        store.write(updated)
        applyToTube(updated)
        return updated
    }

    suspend fun saveCredentials(
        cookie: String,
        visitorData: String,
        dataSyncId: String = "",
        authUser: String = "0",
        accountInfo: AccountInfo? = null,
    ): StoredTubeCredentials {
        val cleanCookie = sanitizeCookieString(cookie)
        val cleanAuthUser = authUser.filter(Char::isDigit).ifBlank { "0" }
        val creds =
            StoredTubeCredentials(
                cookie = cleanCookie,
                visitorData = visitorData.trim(),
                dataSyncId = dataSyncId.trim(),
                authUser = cleanAuthUser,
                accountInfo = accountInfo,
            )
        store.write(creds)
        applyToTube(creds)
        _sessionExpired.value = false
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
        val info =
            try {
                val response = isolated.accountMenu(TubeClient.WEB_REMIX)
                if (!response.status.isSuccess()) return null
                val body = response.bodyAsTextLimited(MAX_AUTH_RESPONSE_BYTES)
                TubeHttpFactory.defaultJson
                    .decodeFromString<AccountMenuResponse>(body)
                    .extractAccountInfo()
            } finally {
                isolated.close()
            }
        if (info == null) return null
        saveCredentials(cookie, visitorData, dataSyncId, authUser, info)
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

    suspend fun signOut() {
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
    }
}

private fun AccountMenuResponse.extractAccountInfo(): AccountInfo? =
    actions.firstNotNullOfOrNull { action ->
        val popup = action.openPopupAction?.popup
        val renderer = popup?.multiPageMenuRenderer ?: popup?.popupContent?.multiPageMenuRenderer
        renderer?.header?.activeAccountHeaderRenderer?.toAccountInfo()
    }

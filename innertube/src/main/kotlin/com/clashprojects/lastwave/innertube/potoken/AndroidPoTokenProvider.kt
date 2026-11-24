package com.clashprojects.lastwave.innertube.potoken

import android.content.Context
import android.os.SystemClock
import com.clashprojects.lastwave.innertube.TubeLogger
import com.clashprojects.lastwave.innertube.d
import com.clashprojects.lastwave.innertube.extract.PoTokenResult
import com.clashprojects.lastwave.innertube.extract.TokenProvider
import com.clashprojects.lastwave.innertube.extract.TokenProviderCapabilities
import com.clashprojects.lastwave.innertube.extract.strategy.PoTokenProviderKind
import com.clashprojects.lastwave.innertube.w
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidPoTokenProvider(
    context: Context,
    private val logger: TubeLogger = TubeLogger.NONE,
) : TokenProvider {
    override val capabilities =
        TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
            usesWebView = true,
        )

    private val mutex = Mutex()
    private val mintScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingMints = mutableMapOf<MintKey, Deferred<PoTokenResult?>>()
    private val resultCache = mutableMapOf<String, CachedResult>()
    private val remote by lazy { RemotePoTokenClient(context) }
    private var minterVisitorData: String? = null
    private var minterCookie: String? = null
    private var unavailableVisitorData: String? = null
    private var unavailableCookie: String? = null
    private var unavailableUntilMs = 0L

    suspend fun prewarm() {
        withContext(Dispatchers.Main.immediate) {
            try {
                remote.prewarm()
            } catch (error: Exception) {
                remote.close()
                logger.d(TAG, "PoToken prewarm failed type=${error::class.simpleName}")
            }
        }
    }

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        withContext(Dispatchers.Main.immediate) {
            awaitPoToken(videoId, visitorData, cookie)
        }

    private suspend fun awaitPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        val key = MintKey(videoId, visitorData, cookie)
        while (true) {
            cachedResult(videoId, visitorData, cookie)?.let { return it }
            val request =
                pendingMints[key] ?: mintScope
                    .async(start = CoroutineStart.LAZY) {
                        mintPoToken(videoId, visitorData, cookie)
                    }.also { req ->
                        pendingMints[key] = req
                        req.invokeOnCompletion {
                            if (pendingMints[key] === req) pendingMints.remove(key)
                        }
                    }
            try {
                return request.await()
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun mintPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? =
        mutex.withLock {
            val nowMs = System.currentTimeMillis()
            resultCache.entries.removeAll { nowMs >= it.value.expiresAtMs }
            val elapsedNowMs = SystemClock.elapsedRealtime()
            cachedResult(videoId, visitorData, cookie)?.let { return@withLock it }
            if (
                visitorData == unavailableVisitorData &&
                cookie == unavailableCookie &&
                elapsedNowMs < unavailableUntilMs
            ) {
                logger.d(TAG, "getPoToken cooldown active")
                return@withLock null
            }

            try {
                if (minterVisitorData != null && (minterVisitorData != visitorData || minterCookie != cookie)) resetMinter()
                val minted = remote.mint(videoId, visitorData, cookie)
                minterVisitorData = visitorData
                minterCookie = cookie
                val result = minted.result
                clearFailureCooldown()
                resultCache[videoId] =
                    CachedResult(
                        visitorData = visitorData,
                        cookie = cookie,
                        result = result,
                        expiresAtMs = minOf(nowMs + TOKEN_CACHE_TTL_MS, minted.expiresAtMs),
                    )
                logger.d(TAG, "getPoToken succeeded elapsed=${SystemClock.elapsedRealtime() - elapsedNowMs}ms")
                result
            } catch (error: CancellationException) {
                resetMinter()
                throw error
            } catch (error: Exception) {
                logger.w(TAG, "getPoToken failed elapsed=${SystemClock.elapsedRealtime() - elapsedNowMs}ms type=${error::class.simpleName}")
                resetMinter()
                unavailableVisitorData = visitorData
                unavailableCookie = cookie
                unavailableUntilMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                null
            }
        }

    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            pendingMints.values.toList().forEach { it.cancel() }
            pendingMints.clear()
            mutex.withLock {
                resetMinter()
                resultCache.clear()
                clearFailureCooldown()
            }
        }
    }

    private fun cachedResult(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        val entry = resultCache[videoId] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAtMs) {
            resultCache.remove(videoId)
            return null
        }
        return entry.result.takeIf { entry.visitorData == visitorData && entry.cookie == cookie }
    }

    private fun resetMinter() {
        remote.close()
        minterVisitorData = null
        minterCookie = null
    }

    private fun clearFailureCooldown() {
        unavailableVisitorData = null
        unavailableCookie = null
        unavailableUntilMs = 0L
    }

    private data class MintKey(
        val videoId: String,
        val visitorData: String,
        val cookie: String?,
    )

    private data class CachedResult(
        val visitorData: String,
        val cookie: String?,
        val result: PoTokenResult,
        val expiresAtMs: Long,
    )

    private companion object {
        private const val TAG = "AndroidPoTokenProvider"
        private const val TOKEN_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}

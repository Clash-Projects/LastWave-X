package com.clashprojects.lastwave.innertube.potoken

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WavePoTokenService : Service() {
    private val sessions = mutableMapOf<String, Session>()

    override fun onCreate() {
        super.onCreate()
        liveInstances++
    }

    override fun onBind(intent: Intent): IBinder =
        Session().also { sessions[requireNotNull(intent.action)] = it }.messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        sessions.remove(intent.action)?.close()
        return false
    }

    override fun onDestroy() {
        sessions.values.forEach(Session::close)
        sessions.clear()
        super.onDestroy()
        liveInstances--
        // Chromium cannot unload in-process; exit this isolated process so ~130MB RAM is reclaimed.
        Handler(Looper.getMainLooper()).post { if (liveInstances == 0) Process.killProcess(Process.myPid()) }
    }

    private companion object {
        var liveInstances = 0
    }

    private inner class Session {
        private val scope = MainScope()
        private var prewarmedWebView = runCatching { WebView(applicationContext) }.getOrNull()
        private var minter: BotGuardWebViewMinter? = null
        private var visitorData: String? = null
        private var cookie: String? = null
        private var playerRequestToken: String? = null
        private var busy = false
        val messenger = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))

        private fun handleMessage(message: Message): Boolean {
            if (message.what != PoTokenIpc.MINT) return false
            val replyTo = message.replyTo ?: return true
            val requestId = message.arg1
            val data = message.data
            val requestedVisitor = data.getString(PoTokenIpc.VISITOR_DATA) ?: return true
            val videoId = data.getString(PoTokenIpc.VIDEO_ID) ?: return true
            if (busy) return true
            busy = true
            scope.launch {
                val response = Bundle()
                try {
                    val requestedCookie = data.getString(PoTokenIpc.COOKIE)
                    if (
                        minter?.let { it.isDead || it.isExpired } != false ||
                        visitorData != requestedVisitor ||
                        cookie != requestedCookie
                    ) {
                        minter?.close()
                        minter = null
                        playerRequestToken = null
                        visitorData = requestedVisitor
                        cookie = requestedCookie
                        val webView = prewarmedWebView
                        prewarmedWebView = null
                        minter =
                            BotGuardWebViewMinter.create(
                                context = this@WavePoTokenService,
                                visitorData = requestedVisitor,
                                cookie = requestedCookie,
                                videoId = videoId,
                                prewarmedWebView = webView,
                            )
                    }
                    val active = requireNotNull(minter)
                    val sessionToken = playerRequestToken ?: active.generate(requestedVisitor).also { playerRequestToken = it }
                    val videoToken = active.generate(videoId)
                    response.putString(PoTokenIpc.PLAYER_TOKEN, sessionToken)
                    response.putString(PoTokenIpc.VIDEO_TOKEN, videoToken)
                    response.putString(PoTokenIpc.VISITOR_DATA, requestedVisitor)
                    response.putLong(PoTokenIpc.EXPIRES_AT, active.expiresAtMs)
                } catch (error: CancellationException) {
                    if (!scope.isActive) throw error
                    resetMinter()
                } catch (_: Exception) {
                    resetMinter()
                } finally {
                    busy = false
                }
                runCatching {
                    replyTo.send(Message.obtain(null, PoTokenIpc.MINT, requestId, 0).apply { this.data = response })
                }
            }
            return true
        }

        private fun resetMinter() {
            minter?.close()
            minter = null
            playerRequestToken = null
            visitorData = null
            cookie = null
        }

        fun close() {
            prewarmedWebView?.destroy()
            prewarmedWebView = null
            scope.cancel()
            resetMinter()
        }
    }
}

internal object PoTokenIpc {
    const val MINT = 1
    const val VIDEO_ID = "videoId"
    const val VISITOR_DATA = "visitorData"
    const val COOKIE = "cookie"
    const val PLAYER_TOKEN = "playerRequestToken"
    const val VIDEO_TOKEN = "streamingDataToken"
    const val EXPIRES_AT = "expiresAtMs"
}

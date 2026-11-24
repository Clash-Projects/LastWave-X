package com.clashprojects.lastwave.innertube

import com.clashprojects.lastwave.innertube.auth.InMemoryTubeCredentialStore
import com.clashprojects.lastwave.innertube.auth.PremiumDetector
import com.clashprojects.lastwave.innertube.auth.TubeAuthManager
import com.clashprojects.lastwave.innertube.auth.hasAuthCookie
import com.clashprojects.lastwave.innertube.auth.isSessionExpiredStatus
import com.clashprojects.lastwave.innertube.cipher.PlayerCipherEngine
import com.clashprojects.lastwave.innertube.extract.AudioQuality
import com.clashprojects.lastwave.innertube.extract.ContentHints
import com.clashprojects.lastwave.innertube.extract.ExtractedStream
import com.clashprojects.lastwave.innertube.extract.StreamExtractor
import com.clashprojects.lastwave.innertube.extract.StreamResolveException
import com.clashprojects.lastwave.innertube.model.TubeClient
import com.clashprojects.lastwave.innertube.net.DohProvider
import com.clashprojects.lastwave.innertube.net.TubeHttpConfig
import com.clashprojects.lastwave.innertube.net.TubeHttpFactory
import com.clashprojects.lastwave.innertube.net.filterPublicMediaAddresses
import com.clashprojects.lastwave.innertube.net.toDns
import com.clashprojects.lastwave.innertube.util.TubeUrlParser
import com.clashprojects.lastwave.innertube.util.parseCookieString
import com.clashprojects.lastwave.innertube.util.sha1
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveTubeModuleTest {
    @Test
    fun cookieAndSapisidAuthParsingWorks() {
        val raw = "Cookie: VISITOR_INFO1_LIVE=abc; __Secure-3PAPISID=secret123/xyz; PREF=hl=en"
        val parsed = parseCookieString(raw)
        assertEquals("secret123/xyz", parsed["__Secure-3PAPISID"])
        assertTrue(hasAuthCookie(raw))
        assertFalse(hasAuthCookie("PREF=hl=en"))
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", sha1("test"))
    }

    @Test
    fun authManagerSyncsAndClearsWaveTubeSession() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"actions":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ktor =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json(TubeHttpFactory.defaultJson) }
                }
            val tube = WaveTube(ktor)
            val store = InMemoryTubeCredentialStore()
            val auth = TubeAuthManager(tube, store)

            auth.saveCredentials(
                cookie = "SAPISID=token_abc; SID=123",
                visitorData = "Cgt2aXNpdG9y",
                dataSyncId = "sync_1",
                authUser = "2",
                detectedPremium = false,
            )
            assertTrue(auth.isSignedIn)
            assertEquals("2", tube.authUser)
            assertEquals("Cgt2aXNpdG9y", tube.visitorData)

            val snap = tube.sessionSnapshot()
            assertTrue(isSessionExpiredStatus(401, authenticated = true))
            assertTrue(auth.notifyExpiredIfMatching(snap))
            assertTrue(auth.sessionExpired.value)
            assertFalse(auth.isSignedIn)

            auth.signOut()
            assertFalse(auth.sessionExpired.value)
            assertEquals("", store.read().cookie)

            auth.close()
            tube.close()
            ktor.close()
        }

    @Test
    fun premiumDetectorRecognizesHeaderLogosAndItags() {
        assertEquals(true, PremiumDetector.detectFromJson("""{"iconType":"MUSIC_PREMIUM_LOGO"}"""))
        assertEquals(false, PremiumDetector.detectFromJson("""{"panelId":"PApremium_upsell"}"""))
        assertNull(PremiumDetector.detectFromJson("""{"responseContext":{}}"""))

        assertTrue(PremiumDetector.isPremiumStream(fakeStream(itag = 774, bitrate = 256_000)))
        assertTrue(PremiumDetector.isPremiumStream(fakeStream(itag = 141, bitrate = 256_000)))
        assertFalse(PremiumDetector.isPremiumStream(fakeStream(itag = 251, bitrate = 135_000)))
    }

    @Test
    fun resolveStreamFallsBackToNormalPlaybackWhenPremiumRouteFails() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"responseContext":{}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ktor =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json(TubeHttpFactory.defaultJson) }
                }
            val tube = WaveTube(ktor)
            val auth = TubeAuthManager(tube, InMemoryTubeCredentialStore())
            auth.saveCredentials(
                cookie = "SAPISID=token_abc",
                visitorData = "Cgt2aXNpdG9y",
                detectedPremium = true,
            )
            assertTrue(auth.isPremium.value)

            val calls = mutableListOf<Pair<Boolean, AudioQuality>>()
            val fakeExtractor =
                object : StreamExtractor {
                    override suspend fun extract(
                        videoId: String,
                        hints: ContentHints,
                        excludedClients: Set<String>,
                        audioQuality: AudioQuality,
                        clientPlaybackNonce: String,
                    ): ExtractedStream? {
                        calls += hints.premium to audioQuality
                        if (hints.premium) {
                            throw StreamResolveException(
                                StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                                "simulated premium client failure",
                            )
                        }
                        return fakeStream(itag = 251, bitrate = 140_000, clientName = "ANDROID_VR")
                    }

                    override suspend fun prewarm() = Unit
                }

            val client =
                WaveTubeClient(
                    okHttpClient = OkHttpClient(),
                    httpClient = ktor,
                    tube = tube,
                    cipherEngine = PlayerCipherEngine(ktor),
                    extractor = fakeExtractor,
                    auth = auth,
                )

            val resolved = client.resolveStream("dQw4w9WgXcQ")
            assertNotNull(resolved)
            assertEquals(251, resolved.itag)
            assertEquals(listOf(true to AudioQuality.HIGH, false to AudioQuality.AUTO), calls)

            // Passive observation on an authenticated 256kbps stream re-confirms Premium; 3 standard auth streams downgrade.
            auth.observeResolvedStream(fakeStream(itag = 251, bitrate = 130_000, clientName = "WEB_REMIX"))
            auth.observeResolvedStream(fakeStream(itag = 251, bitrate = 130_000, clientName = "WEB_REMIX"))
            assertTrue(auth.isPremium.value)
            auth.observeResolvedStream(fakeStream(itag = 251, bitrate = 130_000, clientName = "WEB_REMIX"))
            assertFalse(auth.isPremium.value)
            auth.observeResolvedStream(fakeStream(itag = 774, bitrate = 256_000, clientName = "WEB_REMIX"))
            assertTrue(auth.isPremium.value)

            client.close()
        }

    @Test
    fun dohProvidersAndPublicMediaDnsGuardWork() {
        val base = OkHttpClient()
        assertNotNull(DohProvider.Cloudflare.toDns(base))
        assertNotNull(DohProvider.Google.toDns(base))
        assertNotNull(DohProvider.Quad9.toDns(base))
        assertNotNull(DohProvider.AdGuard.toDns(base))

        val publicIp = listOf(InetAddress.getByName("142.250.190.78"))
        val privateIp = listOf(InetAddress.getByName("192.168.1.10"))

        assertEquals(publicIp, filterPublicMediaAddresses("example.com", publicIp))
        assertEquals(privateIp, filterPublicMediaAddresses("lh3.googleusercontent.com", privateIp))
        assertFailsWith<UnknownHostException> {
            filterPublicMediaAddresses("evil.example", privateIp)
        }
    }

    @Test
    fun waveTubeBrowseSendsExpectedHeadersAndClientContext() =
        runTest {
            var capturedOrigin: String? = null
            var capturedAuth: String? = null
            val mockEngine =
                MockEngine { request ->
                    capturedOrigin = request.headers["Origin"]
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"responseContext":{}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val ktor =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json(TubeHttpFactory.defaultJson) }
                }
            val tube = WaveTube(ktor)
            tube.cookie = "SAPISID=test_sapisid_value"
            tube.visitorData = "Cgt0ZXN0"

            val response = tube.browse(TubeClient.WEB_REMIX, browseId = "FEmusic_home", setLogin = true)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://music.youtube.com", capturedOrigin)
            assertTrue(capturedAuth?.startsWith("SAPISIDHASH ") == true)

            tube.close()
            ktor.close()
        }

    @Test
    fun urlParserAndClientFactoryInstantiateCleanly() =
        runTest {
            assertEquals("dQw4w9WgXcQ", TubeUrlParser.extractVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
            val client = WaveTubeClient.create(TubeHttpConfig(dohProvider = DohProvider.Cloudflare))
            assertNotNull(client.extractor)
            assertNotNull(client.cipherEngine)
            client.close()
        }

    private fun fakeStream(
        itag: Int,
        bitrate: Int,
        clientName: String = "WEB_REMIX",
    ) = ExtractedStream(
        videoId = "dQw4w9WgXcQ",
        audioUrl = "https://rr1---sn.googlevideo.com/videoplayback?itag=$itag",
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        contentLengthBytes = null,
        itag = itag,
        mimeType = "audio/webm",
        codecs = "opus",
        bitrate = bitrate,
        sampleRate = 48_000,
        clientName = clientName,
        profileId = clientName,
        requireBoundedRange = false,
        rangeChunkSizeBytes = 0L,
    )
}

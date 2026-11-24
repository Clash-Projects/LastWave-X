package com.clashprojects.lastwave.innertube.extract.strategy

import com.clashprojects.lastwave.innertube.extract.ContentHints
import com.clashprojects.lastwave.innertube.model.TubeClient

enum class PlaybackTransportPreference {
    AUTO,
    DIRECT,
    SABR,
    HLS,
}

data class ClientSelectionRequest(
    val hints: ContentHints,
    val authenticated: Boolean,
    val premium: Boolean = false,
    val availablePoTokenProviders: Set<PoTokenProviderKind> = emptySet(),
    val javaScriptRuntimeAvailable: Boolean = true,
    val webViewAvailable: Boolean = false,
    val fastPathOnly: Boolean = false,
    val transportPreference: PlaybackTransportPreference = PlaybackTransportPreference.AUTO,
    val excludedClients: Set<String> = emptySet(),
)

data class SelectedClient(
    val client: TubeClient,
    val manifest: ClientProfileManifest? = null,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
)

data class RejectedClient(
    val manifest: ClientProfileManifest,
    val reasons: List<String>,
)

data class ClientSelectionResult(
    val candidates: List<SelectedClient>,
    val rejected: List<RejectedClient> = emptyList(),
)

interface FallbackPolicy {
    fun resolveClients(hints: ContentHints): List<TubeClient>

    fun selectClients(request: ClientSelectionRequest): ClientSelectionResult =
        ClientSelectionResult(
            resolveClients(request.hints).map { client -> SelectedClient(client, ClientProfileCatalog.findManifest(client)) },
        )
}

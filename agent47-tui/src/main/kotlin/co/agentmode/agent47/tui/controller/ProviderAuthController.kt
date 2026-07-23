package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.coding.core.auth.AuthMethod
import co.agentmode.agent47.coding.core.auth.OAuthAuthorization
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.ui.core.state.SelectItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns provider connection: choosing an auth method, entering an API key, and driving the OAuth
 * device-code flow with its polling on a suspend path off the overlay callbacks.
 */
@Stable
internal class ProviderAuthController(
    private val state: TuiAppState,
    private val feed: TranscriptFeed,
    private val models: ModelController,
    private val scope: CoroutineScope,
    private val storeApiKey: (provider: String, apiKey: String) -> Unit,
    private val storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit,
    private val authorizeOAuth: suspend (provider: String) -> OAuthAuthorization?,
    private val pollOAuthToken: suspend (provider: String) -> OAuthResult?,
) {
    fun startProviderAuth(info: ProviderInfo) {
        val methods = info.authMethods
        if (methods.size == 1) {
            startAuthMethod(info, methods.first())
        } else {
            val methodOptions = methods.map { method ->
                SelectItem(label = method.label, value = method)
            }
            state.overlays.push(
                title = "${info.name} — Auth Method",
                items = methodOptions,
                selectedIndex = 0,
                onSubmit = { method -> startAuthMethod(info, method) },
            )
        }
    }

    fun startAuthMethod(info: ProviderInfo, method: AuthMethod) {
        when (method) {
            is AuthMethod.ApiKey -> {
                state.overlays.pushPrompt(
                    title = "${info.name} — API Key",
                    placeholder = "Paste your API key",
                    masked = true,
                    onSubmit = { apiKey ->
                        if (apiKey.isBlank()) {
                            feed.appendSystemMessage("No API key provided")
                        } else {
                            storeApiKey(info.id, apiKey)
                            models.onProviderConnected(info)
                        }
                    },
                )
            }

            is AuthMethod.OAuth -> {
                state.overlays.pushInfo(
                    title = "${info.name} — Authorizing",
                    lines = listOf(
                        "Starting authorization...",
                        "",
                        "Please wait...",
                    ),
                )
                scope.launch { runOAuthFlow(info) }
            }
        }
    }

    private suspend fun runOAuthFlow(info: ProviderInfo) {
        val authorization = runCatching { authorizeOAuth(info.id) }.getOrNull()
        if (authorization == null) {
            state.overlays.dismissTopSilent()
            feed.appendSystemMessage("Failed to start OAuth for ${info.name}")
            return
        }
        // Update the info overlay with the verification URL and user code
        state.overlays.dismissTopSilent()
        var cancelled = false
        state.overlays.pushInfo(
            title = "${info.name} — Waiting for Authorization",
            lines = listOf(
                "Open this URL in your browser:",
                "",
                "  ${authorization.verificationUrl}",
                "",
                "Enter code: ${authorization.userCode}",
                "",
                "Waiting for authorization...",
            ),
            onClose = { cancelled = true },
        )
        // Try to open the browser
        runCatching {
            ProcessBuilder("open", authorization.verificationUrl).start()
        }
        val result = runCatching { pollOAuthToken(info.id) }.getOrNull()
        state.overlays.dismissTopSilent()
        if (cancelled) return
        when (result) {
            is OAuthResult.Success -> {
                storeOAuthCredential(info.id, result.credential)
                models.onProviderConnected(info)
            }

            is OAuthResult.Failed -> {
                feed.appendSystemMessage("Authorization failed: ${result.message}")
            }

            null -> {
                feed.appendSystemMessage("OAuth flow was cancelled or unavailable")
            }
        }
    }
}

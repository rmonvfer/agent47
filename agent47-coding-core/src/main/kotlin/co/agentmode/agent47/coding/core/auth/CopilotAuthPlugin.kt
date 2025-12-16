package co.agentmode.agent47.coding.core.auth

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val GITHUB_CLIENT_ID = "Ov23li8tweQw6odWQebz"
private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
private const val POLLING_SAFETY_MARGIN_MS = 3000L

/**
 * Authenticates with GitHub Copilot using the OAuth device code flow.
 *
 * Requests a device code from GitHub, asks the user to visit a verification
 * URL and enter the code, then polls until the authorization is granted.
 * The resulting access token is stored as an OAuthCredential and used
 * as a Bearer token on Copilot API requests.
 */
public class CopilotAuthPlugin : ProviderAuthPlugin {

    override val providerId: String = "github-copilot"

    override val methods: List<AuthMethod> = listOf(
        AuthMethod.OAuth(label = "Login with GitHub Copilot"),
        AuthMethod.ApiKey(label = "Paste token manually"),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    private var pendingDeviceCode: String? = null
    private var pendingInterval: Long = 5

    override suspend fun authorize(): OAuthAuthorization {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DEVICE_CODE_URL))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"client_id":"$GITHUB_CLIENT_ID","scope":"read:user"}"""
                )
            )
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to initiate device authorization (HTTP ${response.statusCode()})")
        }

        val body = json.parseToJsonElement(response.body()).jsonObject
        val verificationUri = body["verification_uri"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing verification_uri in device code response")
        val userCode = body["user_code"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing user_code in device code response")
        val deviceCode = body["device_code"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing device_code in device code response")
        val interval = body["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L

        pendingDeviceCode = deviceCode
        pendingInterval = interval

        return OAuthAuthorization(
            verificationUrl = verificationUri,
            userCode = userCode,
            instructions = "Enter code: $userCode",
        )
    }

    override suspend fun pollForToken(): OAuthResult {
        val deviceCode = pendingDeviceCode
            ?: return OAuthResult.Failed("No pending authorization — call authorize() first")

        var currentInterval = pendingInterval

        while (true) {
            delay(currentInterval * 1000 + POLLING_SAFETY_MARGIN_MS)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(ACCESS_TOKEN_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"client_id":"$GITHUB_CLIENT_ID","device_code":"$deviceCode","grant_type":"urn:ietf:params:oauth:grant-type:device_code"}"""
                    )
                )
                .build()

            val response = runCatching {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }.getOrElse { error ->
                return OAuthResult.Failed("Network error: ${error.message}")
            }

            if (response.statusCode() != 200) {
                return OAuthResult.Failed("Token request failed (HTTP ${response.statusCode()})")
            }

            val body = json.parseToJsonElement(response.body()).jsonObject
            val accessToken = body["access_token"]?.jsonPrimitive?.content
            val error = body["error"]?.jsonPrimitive?.content

            if (accessToken != null) {
                pendingDeviceCode = null
                return OAuthResult.Success(
                    credential = OAuthCredential(
                        accessToken = accessToken,
                        refreshToken = accessToken,
                        expires = 0,
                    ),
                )
            }

            when (error) {
                "authorization_pending" -> continue

                "slow_down" -> {
                    // RFC 8628 Section 3.5: add 5 seconds to the polling interval
                    val serverInterval = body["interval"]?.jsonPrimitive?.content?.toLongOrNull()
                    currentInterval = if (serverInterval != null && serverInterval > 0) {
                        serverInterval
                    } else {
                        currentInterval + 5
                    }
                    continue
                }

                "expired_token" -> {
                    pendingDeviceCode = null
                    return OAuthResult.Failed("Device code expired — please try again")
                }

                else -> {
                    pendingDeviceCode = null
                    return OAuthResult.Failed(error ?: "Unknown error")
                }
            }
        }
    }
}

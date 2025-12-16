package co.agentmode.agent47.coding.core.auth

/**
 * Describes an authentication method that a provider supports.
 *
 * Providers may support multiple methods (e.g. both OAuth and API key).
 * The TUI uses this to decide which UI flow to present.
 */
public sealed interface AuthMethod {
    public val label: String

    /**
     * Standard API key authentication — user pastes a key.
     */
    public data class ApiKey(
        override val label: String = "API Key",
    ) : AuthMethod

    /**
     * OAuth device code flow — the app requests a device code from the provider,
     * opens a URL in the browser, and polls for authorization completion.
     */
    public data class OAuth(
        override val label: String,
    ) : AuthMethod
}

/**
 * Result of initiating an OAuth device code flow.
 *
 * Contains the verification URL and user code to display, plus a
 * suspend function that polls until the user completes authorization.
 */
public data class OAuthAuthorization(
    val verificationUrl: String,
    val userCode: String,
    val instructions: String,
)

/**
 * Outcome of an OAuth authorization attempt.
 */
public sealed interface OAuthResult {
    public data class Success(
        val credential: OAuthCredential,
    ) : OAuthResult

    public data class Failed(
        val message: String = "Authorization failed",
    ) : OAuthResult
}

/**
 * Plugin that provides authentication capabilities for a specific provider.
 *
 * Implementations declare the auth methods they support and handle the
 * OAuth device code flow when applicable.
 */
public interface ProviderAuthPlugin {
    /**
     * The provider ID this plugin handles (e.g. "github-copilot").
     */
    public val providerId: String

    /**
     * Authentication methods this provider supports, in display order.
     */
    public val methods: List<AuthMethod>

    /**
     * Initiates an OAuth device code flow.
     *
     * Returns the verification URL and user code to display to the user.
     * After calling this, use [pollForToken] to wait for the user to
     * complete authorization in the browser.
     *
     * @throws UnsupportedOperationException if the provider does not support OAuth
     */
    public suspend fun authorize(): OAuthAuthorization

    /**
     * Polls the provider until the user completes the OAuth device flow.
     *
     * This is a long-running suspend function that blocks until authorization
     * completes or fails. Call after [authorize] returns.
     */
    public suspend fun pollForToken(): OAuthResult
}

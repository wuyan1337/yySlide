package sh.haven.core.ssh

/**
 * A server-issued keyboard-interactive challenge. An SSH server performing
 * `keyboard-interactive` auth sends one of these per round, typically asking
 * for a password + a 2FA / TOTP code in separate rounds, or both at once.
 *
 * The host should present [instruction] and each [Prompt.text] to the user,
 * collecting one response per prompt. Responses are returned in the same
 * order as [prompts].
 */
data class KeyboardInteractiveChallenge(
    /** "user@host" or similar — context for the UI so the user knows which
     *  session is being authenticated. May be empty. */
    val destination: String,
    /** Server-provided challenge name (e.g. "Google Authenticator"). May be empty. */
    val name: String,
    /** Server-provided instructions shown above the prompts. May be empty. */
    val instruction: String,
    val prompts: List<Prompt>,
    /**
     * Optional per-prompt suggested answers, parallel to [prompts]. A
     * non-null entry pre-fills the corresponding field in the UI — used
     * by the TOTP auto-fill path (#178) when the profile asks to confirm
     * the generated code before sending rather than auto-submitting.
     * Empty (the default) means no pre-fill.
     */
    val prefilled: List<String?> = emptyList(),
) {
    data class Prompt(
        val text: String,
        /** When false (the usual case for passwords / codes) the response
         *  should be masked in the UI. */
        val echo: Boolean,
    )
}

/**
 * Answers a [KeyboardInteractiveChallenge] from the SSH server. Implementations
 * typically surface a dialog and suspend until the user submits or cancels.
 *
 * Return a list of responses with the same length as [KeyboardInteractiveChallenge.prompts],
 * or `null` to abort authentication. JSch treats a null here as "user declined" and
 * stops attempting keyboard-interactive auth on this session.
 */
fun interface KeyboardInteractivePrompter {
    suspend fun prompt(challenge: KeyboardInteractiveChallenge): List<String>?
}

package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking

private const val KI_TAG = "yySlideKI"

/**
 * Bridges JSch's synchronous [UIKeyboardInteractive] callback (invoked on
 * JSch's internal IO thread during auth) to a suspending
 * [KeyboardInteractivePrompter]. The JSch thread blocks in [runBlocking]
 * until the prompter resumes — which, for a UI-backed prompter, means
 * until the user dismisses the dialog.
 *
 * Also implements [UserInfo] because JSch's API requires any interactive
 * callback to come in as a single object that implements both interfaces.
 * We deliberately return nulls / false from the [UserInfo] methods so JSch
 * falls back to the password set via `session.setPassword(...)` for
 * password auth, and cancels prompts we don't handle (host-key acceptance
 * is handled separately by [HostKeyVerifier]).
 *
 * An optional [fallbackPassword] auto-answers the first prompt when it
 * looks like a password prompt — i.e. the server sent exactly one prompt
 * with echo=false, and we already have a saved password for the profile.
 * This matches Gboard-style UX: the user shouldn't have to retype their
 * saved password just because the server routed "Password:" through the
 * keyboard-interactive channel instead of password auth.
 *
 * An optional [totpCodeProvider] (#178) generates a live TOTP code on
 * demand for OTP-looking prompts (echo=false + an OTP keyword). When
 * every prompt in a round can be auto-answered (password via
 * [fallbackPassword], OTP via [totpCodeProvider]) and [autoSubmit] is
 * true, the round is answered without showing any UI. Otherwise the
 * prompter is invoked with the auto-answers carried in
 * [KeyboardInteractiveChallenge.prefilled] so the dialog can pre-populate
 * (the per-profile "confirm OTP before sending" path).
 */
internal class KeyboardInteractiveUserInfo(
    private val destination: String,
    private val prompter: KeyboardInteractivePrompter,
    private val fallbackPassword: CharArray? = null,
    private val totpCodeProvider: (() -> String)? = null,
    private val autoSubmit: Boolean = true,
) : UserInfo, UIKeyboardInteractive {

    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) { /* no-op */ }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        val prompts = (prompt ?: emptyArray()).mapIndexed { i, p ->
            KeyboardInteractiveChallenge.Prompt(
                text = p,
                echo = echo?.getOrNull(i) ?: true,
            )
        }
        Log.d(
            KI_TAG,
            "promptKeyboardInteractive name='$name' instruction='$instruction' " +
                "prompts=${prompts.map { "${it.text}(echo=${it.echo})" }}",
        )

        // Compute a per-prompt auto-answer: a saved password for a
        // password prompt, a freshly generated TOTP code for an
        // OTP-looking prompt. Generated at this instant so the code is
        // current for the window the server will validate against.
        val autoAnswers: List<String?> = prompts.map { p ->
            when {
                fallbackPassword != null && !p.echo && p.text.contains("password", ignoreCase = true) ->
                    String(fallbackPassword)
                totpCodeProvider != null && looksLikeOtpPrompt(p) -> totpCodeProvider.invoke()
                else -> null
            }
        }

        // Every prompt answerable from stored secrets and auto-submit
        // enabled: answer the round with no UI at all.
        if (autoSubmit && prompts.isNotEmpty() && autoAnswers.all { it != null }) {
            Log.d(KI_TAG, "  auto-answering ${prompts.size} prompt(s) from stored secrets")
            return autoAnswers.map { it!! }.toTypedArray()
        }

        val challenge = KeyboardInteractiveChallenge(
            destination = destination ?: this.destination,
            name = name ?: "",
            instruction = instruction ?: "",
            prompts = prompts,
            // Carry any partial auto-answers so the dialog pre-fills them
            // (e.g. the confirm-OTP-before-sending path).
            prefilled = if (autoAnswers.any { it != null }) autoAnswers else emptyList(),
        )
        Log.d(KI_TAG, "  dispatching to prompter")
        val responses = runBlocking { prompter.prompt(challenge) }
        Log.d(
            KI_TAG,
            "  prompter returned: ${if (responses == null) "null (cancel)" else "${responses.size} responses, " +
                "lengths=${responses.map { it.length }}"}",
        )
        return responses?.toTypedArray()
    }

    /**
     * Heuristic for "this prompt wants a TOTP / one-time code". PAM OTP
     * modules (google-authenticator, oath-toolkit, Duo, etc.) phrase the
     * prompt in varied ways but it's always a masked field. We require
     * echo=false AND an OTP-ish keyword so a plain "Password:" prompt
     * isn't answered with a TOTP code by mistake.
     */
    private fun looksLikeOtpPrompt(p: KeyboardInteractiveChallenge.Prompt): Boolean {
        if (p.echo) return false
        val t = p.text.lowercase()
        return OTP_KEYWORDS.any { it in t }
    }

    private companion object {
        val OTP_KEYWORDS = listOf(
            "verification code", "one-time", "one time", "otp", "totp",
            "token", "authenticator", "2fa", "two-factor", "two factor",
            "code:", "code ", "passcode",
        )
    }
}

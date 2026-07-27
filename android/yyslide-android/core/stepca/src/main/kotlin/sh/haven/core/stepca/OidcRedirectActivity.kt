package sh.haven.core.stepca

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the OIDC redirect (`haven://stepca-callback?code=...&state=...`)
 * and hands the result to [OidcRedirectBus]. No UI — finishes immediately
 * after parsing. The Custom Tab the user came from also closes itself
 * once the redirect URL fires.
 *
 * Marked `singleTask` in the manifest so quick re-redirects (e.g. after
 * a second OIDC login attempt) reuse this instance via [onNewIntent]
 * rather than stacking duplicate Activities on top of the user's UI.
 */
class OidcRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
        finish()
    }

    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        val state = uri.getQueryParameter("state") ?: return
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val description = uri.getQueryParameter("error_description")
            OidcRedirectBus.fail(state, if (description != null) "$error: $description" else error)
            return
        }
        val code = uri.getQueryParameter("code")
        if (code != null) {
            OidcRedirectBus.complete(state, code)
        } else {
            OidcRedirectBus.fail(state, "Redirect missing code parameter")
        }
    }
}

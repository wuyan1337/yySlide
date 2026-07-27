package sh.haven.core.ui

import android.view.KeyEvent

/**
 * Singleton bridge for intercepting hardware keyboard events at the Activity level,
 * before the View hierarchy processes them.
 *
 * TerminalScreen registers a handler that uses Android's KeyCharacterMap
 * (via [KeyEvent.getUnicodeChar]) to produce layout-correct characters.
 * Without this, termlib's hardcoded US QWERTY symbol mappings produce
 * wrong characters on non-US layouts (e.g. German QWERTZ, French AZERTY).
 *
 * The handler is invoked from [android.app.Activity.dispatchKeyEvent].
 */
object KeyEventInterceptor {
    var handler: ((KeyEvent) -> Boolean)? = null
}

package sh.haven.feature.sftp.attach

/**
 * POSIX single-quote escape: wraps [s] in `'...'` and rewrites every `'`
 * inside as `'\''`. Safe for any byte sequence — including spaces, `$`,
 * backticks, backslashes and newlines — when pasted into a POSIX shell.
 */
fun shellQuote(s: String): String {
    if (s.isEmpty()) return "''"
    return "'" + s.replace("'", "'\\''") + "'"
}

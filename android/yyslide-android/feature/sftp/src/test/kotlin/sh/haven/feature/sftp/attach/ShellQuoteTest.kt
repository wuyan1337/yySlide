package sh.haven.feature.sftp.attach

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {

    @Test fun emptyString() {
        assertEquals("''", shellQuote(""))
    }

    @Test fun plainPath() {
        assertEquals("'/tmp/file.txt'", shellQuote("/tmp/file.txt"))
    }

    @Test fun pathWithSpaces() {
        assertEquals("'/tmp/my file.txt'", shellQuote("/tmp/my file.txt"))
    }

    @Test fun singleQuoteInside() {
        assertEquals("'it'\\''s.txt'", shellQuote("it's.txt"))
    }

    @Test fun multipleSingleQuotes() {
        assertEquals("''\\'''\\'''", shellQuote("''"))
    }

    @Test fun shellMetacharsArePreserved() {
        assertEquals("'\$HOME/`id`/\\n'", shellQuote("\$HOME/`id`/\\n"))
    }

    @Test fun rcloneRemoteReference() {
        assertEquals("'gdrive:Photos/holiday 2025/IMG_001.jpg'",
            shellQuote("gdrive:Photos/holiday 2025/IMG_001.jpg"))
    }
}

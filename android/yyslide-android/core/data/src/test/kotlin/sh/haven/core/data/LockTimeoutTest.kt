package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository.LockTimeout

class LockTimeoutTest {

    // ---- fromString() ----

    @Test
    fun `fromString returns IMMEDIATE for null`() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromString(null))
    }

    @Test
    fun `fromString returns IMMEDIATE for empty string`() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromString(""))
    }

    @Test
    fun `fromString returns IMMEDIATE for unrecognised string`() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromString("invalid"))
    }

    @Test
    fun `fromString returns IMMEDIATE for IMMEDIATE`() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromString("IMMEDIATE"))
    }

    @Test
    fun `fromString returns THIRTY_SECONDS for THIRTY_SECONDS`() {
        assertEquals(LockTimeout.THIRTY_SECONDS, LockTimeout.fromString("THIRTY_SECONDS"))
    }

    @Test
    fun `fromString returns ONE_MINUTE for ONE_MINUTE`() {
        assertEquals(LockTimeout.ONE_MINUTE, LockTimeout.fromString("ONE_MINUTE"))
    }

    @Test
    fun `fromString returns FIVE_MINUTES for FIVE_MINUTES`() {
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.fromString("FIVE_MINUTES"))
    }

    @Test
    fun `fromString returns NEVER for NEVER`() {
        assertEquals(LockTimeout.NEVER, LockTimeout.fromString("NEVER"))
    }

    @Test
    fun `fromString is case sensitive and returns IMMEDIATE for lowercase`() {
        // Enum name matching is exact — "immediate" does not match "IMMEDIATE"
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.fromString("immediate"))
    }

    // ---- seconds values ----

    @Test
    fun `IMMEDIATE has seconds value of 0`() {
        assertEquals(0L, LockTimeout.IMMEDIATE.seconds)
    }

    @Test
    fun `THIRTY_SECONDS has seconds value of 30`() {
        assertEquals(30L, LockTimeout.THIRTY_SECONDS.seconds)
    }

    @Test
    fun `ONE_MINUTE has seconds value of 60`() {
        assertEquals(60L, LockTimeout.ONE_MINUTE.seconds)
    }

    @Test
    fun `FIVE_MINUTES has seconds value of 300`() {
        assertEquals(300L, LockTimeout.FIVE_MINUTES.seconds)
    }

    @Test
    fun `NEVER has seconds value of Long MAX_VALUE`() {
        assertEquals(Long.MAX_VALUE, LockTimeout.NEVER.seconds)
    }

    // ---- roundtrip: name() -> fromString() ----

    @Test
    fun `all entries roundtrip through name and fromString`() {
        LockTimeout.entries.forEach { timeout ->
            assertEquals(
                "fromString(${timeout.name}) should return $timeout",
                timeout,
                LockTimeout.fromString(timeout.name),
            )
        }
    }
}

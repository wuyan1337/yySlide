package sh.haven.core.knock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnockSequenceTest {

    @Test
    fun `blank input parses to null - knocking disabled`() {
        assertNull(KnockSequence.parse(null).getOrThrow())
        assertNull(KnockSequence.parse("").getOrThrow())
        assertNull(KnockSequence.parse("   ").getOrThrow())
        assertNull(KnockSequence.parse("\t\n").getOrThrow())
    }

    @Test
    fun `space-separated ports default to tcp`() {
        val seq = KnockSequence.parse("7000 8000 9000").getOrThrow()
        assertNotNull(seq)
        assertEquals(
            listOf(
                KnockStep(7000, KnockStep.Protocol.TCP),
                KnockStep(8000, KnockStep.Protocol.TCP),
                KnockStep(9000, KnockStep.Protocol.TCP),
            ),
            seq!!.steps,
        )
    }

    @Test
    fun `comma-separated ports work`() {
        val seq = KnockSequence.parse("7000,8000,9000").getOrThrow()!!
        assertEquals(3, seq.steps.size)
        assertTrue(seq.steps.all { it.protocol == KnockStep.Protocol.TCP })
    }

    @Test
    fun `mixed whitespace and commas tolerated`() {
        val seq = KnockSequence.parse("7000, 8000,9000  ,  10000").getOrThrow()!!
        assertEquals(listOf(7000, 8000, 9000, 10000), seq.steps.map { it.port })
    }

    @Test
    fun `protocol suffix is honoured`() {
        val seq = KnockSequence.parse("7000/tcp 8000/udp 9000/TCP").getOrThrow()!!
        assertEquals(KnockStep.Protocol.TCP, seq.steps[0].protocol)
        assertEquals(KnockStep.Protocol.UDP, seq.steps[1].protocol)
        assertEquals(KnockStep.Protocol.TCP, seq.steps[2].protocol)
    }

    @Test
    fun `colon separator also accepted (knockd style)`() {
        val seq = KnockSequence.parse("7000:tcp,8000:udp").getOrThrow()!!
        assertEquals(KnockStep.Protocol.TCP, seq.steps[0].protocol)
        assertEquals(KnockStep.Protocol.UDP, seq.steps[1].protocol)
    }

    @Test
    fun `delay is carried through`() {
        val seq = KnockSequence.parse("7000", delayMs = 250).getOrThrow()!!
        assertEquals(250, seq.interKnockDelayMs)
    }

    @Test
    fun `non-numeric port fails`() {
        assertTrue(KnockSequence.parse("abc").isFailure)
        assertTrue(KnockSequence.parse("7000 abc 9000").isFailure)
    }

    @Test
    fun `port out of range fails`() {
        assertTrue(KnockSequence.parse("0").isFailure)
        assertTrue(KnockSequence.parse("70000").isFailure)
        assertTrue(KnockSequence.parse("-1").isFailure)
    }

    @Test
    fun `unknown protocol fails`() {
        val r = KnockSequence.parse("22/sctp")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("sctp", ignoreCase = true) == true)
    }

    @Test
    fun `format roundtrips through parse`() {
        val original = KnockSequence.parse("7000/tcp 8000/udp 9000/tcp").getOrThrow()!!
        val reparsed = KnockSequence.parse(original.format()).getOrThrow()!!
        assertEquals(original.steps, reparsed.steps)
    }

    @Test
    fun `negative delay fails construction`() {
        val r = runCatching {
            KnockSequence(listOf(KnockStep(7000, KnockStep.Protocol.TCP)), -1)
        }
        assertTrue(r.isFailure)
    }
}

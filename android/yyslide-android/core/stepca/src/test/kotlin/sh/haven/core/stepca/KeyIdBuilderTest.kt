package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyIdBuilderTest {

    @Test
    fun `sanitises label punctuation`() {
        // Special chars become single dashes; the model component is
        // mocked-out under JVM (Build.MODEL is null), so just check the
        // shape and the label half.
        val keyId = KeyIdBuilder.build("alice@example.com:user!")
        assertTrue(
            "keyId should start with sanitised label: $keyId",
            keyId.startsWith("alice@example.com-user@") ||
                keyId.startsWith("alice@example.com-user-@"),
        )
    }

    @Test
    fun `defaults to haven for an empty or punctuation-only label`() {
        val keyId = KeyIdBuilder.build("!!!")
        assertTrue(keyId.startsWith("haven@"))
    }
}

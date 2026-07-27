package sh.haven.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YySlideCloudRepositoryUrlTest {
    @Test
    fun `adds https and removes trailing slash`() {
        assertEquals(
            "https://cloud.example.com",
            YySlideCloudRepository.normalizeServerUrl(" cloud.example.com/ "),
        )
    }

    @Test
    fun `allows cleartext only for configured loopback hosts`() {
        assertEquals(
            "http://127.0.0.1:8080",
            YySlideCloudRepository.normalizeServerUrl("http://127.0.0.1:8080/"),
        )
        assertEquals(
            "http://localhost:8080",
            YySlideCloudRepository.normalizeServerUrl("http://localhost:8080/"),
        )
    }

    @Test
    fun `rejects cleartext remote servers with a readable error`() {
        val error = runCatching {
            YySlideCloudRepository.normalizeServerUrl("http://cloud.example.com")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("requires HTTPS"))
    }

    @Test
    fun `rejects URL components that break API path construction`() {
        val error = runCatching {
            YySlideCloudRepository.normalizeServerUrl("https://cloud.example.com?token=secret")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("query"))
    }
}

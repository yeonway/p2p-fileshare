package site.sexyminup.p2pfileshare.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferUtilsTest {
    @Test
    fun chunkCountRoundsUp() {
        assertEquals(0, chunkCount(0, 65_536))
        assertEquals(1, chunkCount(1, 65_536))
        assertEquals(1, chunkCount(65_536, 65_536))
        assertEquals(2, chunkCount(65_537, 65_536))
    }

    @Test
    fun urlValidationAllowsHttpsAndLocalHttpOnly() {
        assertTrue(isAllowedServerUrl("https://files.dcout.site"))
        assertTrue(isAllowedServerUrl("https://files.sexyminup.site"))
        assertTrue(isAllowedServerUrl("http://127.0.0.1:8010"))
        assertTrue(isAllowedServerUrl("http://localhost:8010"))
        assertFalse(isAllowedServerUrl("http://example.com"))
    }

    @Test
    fun androidSenderCapsChunkSizeForWebRtcCompatibility() {
        assertEquals(65_536, resolveDataChannelChunkSize(null))
        assertEquals(32_768, resolveDataChannelChunkSize(32_768))
        assertEquals(65_536, resolveDataChannelChunkSize(1_048_576))
        assertEquals(65_536, resolveDataChannelChunkSize(0))
    }

    @Test
    fun filenameSanitizeDropsPath() {
        assertEquals("video.mp4", sanitizeDisplayFileName("../secret/video.mp4"))
        assertEquals("file", sanitizeDisplayFileName(".."))
    }
}

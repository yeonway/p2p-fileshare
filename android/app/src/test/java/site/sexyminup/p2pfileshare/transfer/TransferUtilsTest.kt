package site.sexyminup.p2pfileshare.transfer

import kotlinx.serialization.json.Json
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
    fun urlValidationAllowsOnlyFixedProductionDomain() {
        assertTrue(isAllowedServerUrl("https://files.dcout.site"))
        assertTrue(isAllowedServerUrl("https://files.dcout.site/"))
        assertFalse(isAllowedServerUrl("https://files.sexyminup.site"))
        assertFalse(isAllowedServerUrl("http://127.0.0.1:8010"))
        assertFalse(isAllowedServerUrl("http://localhost:8010"))
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

    @Test
    fun tarHelpersCreatePortableArchiveParts() {
        assertEquals(0L, 0L.roundUpToTarBlock())
        assertEquals(512L, 1L.roundUpToTarBlock())
        assertEquals(512L, 512L.roundUpToTarBlock())
        assertEquals(1024L, 513L.roundUpToTarBlock())
        assertEquals("001_video.mp4", tarEntryPath("../secret/video.mp4", 0))

        val header = tarHeader("folder/file.txt", 123)
        assertEquals(512, header.size)
        assertEquals('0'.code.toByte(), header[156])
        assertEquals('_'.code.toByte(), header[6])

        val pax = paxRecords("001_한글.txt", 123).decodeToString()
        assertTrue(pax.contains("path=001_한글.txt\n"))
        assertTrue(pax.contains("size=123\n"))
    }

    @Test
    fun transferControlMessagesKeepTypeWhenDefaultEncodingIsDisabled() {
        val json = Json { encodeDefaults = false }
        val manifest = json.encodeManifest(
            ManifestMessage(
                transferId = "transfer-1",
                fileName = "video.mp4",
                fileSize = 10,
                mimeType = "video/mp4",
                chunkSize = 65_536,
                chunkCount = 1,
            ),
        )
        val ack = json.encodeAck(AckMessage(receivedBytes = 0, lastChunkIndex = -1))
        val senderFinished = json.encodeSenderFinished(
            SenderFinishedMessage(transferId = "transfer-1", totalBytes = 10, lastChunkIndex = 0),
        )
        val receiverComplete = json.encodeReceiverComplete(
            ReceiverCompleteMessage(transferId = "transfer-1", totalBytes = 10, lastChunkIndex = 0),
        )
        val error = json.encodeError(ErrorMessage(transferId = "transfer-1", message = "failed"))

        assertTrue(manifest.contains("\"type\":\"manifest\""))
        assertTrue(ack.contains("\"type\":\"ack\""))
        assertTrue(senderFinished.contains("\"type\":\"sender-finished\""))
        assertTrue(receiverComplete.contains("\"type\":\"receiver-complete\""))
        assertTrue(error.contains("\"type\":\"error\""))
    }
}

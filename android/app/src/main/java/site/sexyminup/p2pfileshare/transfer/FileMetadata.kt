package site.sexyminup.p2pfileshare.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.charset.StandardCharsets
import java.util.Locale

data class FileMetadata(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
)

fun queryFileMetadata(contentResolver: ContentResolver, uri: Uri): FileMetadata {
    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    var name = "file"
    var size = -1L
    val cursor: Cursor? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) {
                name = it.getString(nameIndex) ?: name
            }
            if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                size = it.getLong(sizeIndex)
            }
        }
    }
    require(size >= 0) { "파일 크기를 확인할 수 없습니다. 다른 파일 선택기를 사용해 주세요." }
    return FileMetadata(
        uri = uri,
        name = sanitizeDisplayFileName(name),
        size = size,
        mimeType = mimeType,
    )
}

fun sanitizeDisplayFileName(value: String): String {
    val fileName = value.replace('\\', '/').substringAfterLast('/').trim().trim('.', ' ')
    return fileName.takeIf { it.isNotBlank() }?.take(255) ?: "file"
}

fun tarArchiveName(fileCount: Int): String = "send_honey_where_${fileCount}_files.tar"

fun tarArchiveSize(files: List<FileMetadata>): Long {
    require(files.isNotEmpty()) { "files must not be empty" }
    return files.withIndex().fold(END_OF_ARCHIVE_BYTES) { total, indexed ->
        val file = indexed.value
        require(file.size >= 0) { "file size must not be negative" }
        val path = tarEntryPath(file.name, indexed.index)
        val paxSize = paxRecords(path, file.size).size.toLong()
        total.checkedAdd(TAR_BLOCK_SIZE.toLong())
            .checkedAdd(paxSize.roundUpToTarBlock())
            .checkedAdd(TAR_BLOCK_SIZE.toLong())
            .checkedAdd(file.size.roundUpToTarBlock())
    }
}

fun tarEntryPath(fileName: String, index: Int): String {
    val safeName = sanitizeDisplayFileName(fileName)
    val numbered = "%03d_%s".format(Locale.US, index + 1, safeName)
    return numbered.replace('/', '_').replace('\\', '_')
}

fun tarHeader(name: String, size: Long, typeFlag: Byte = '0'.code.toByte()): ByteArray {
    val header = ByteArray(TAR_BLOCK_SIZE)
    writeAscii(header, 0, 100, safeTarHeaderName(name))
    writeOctal(header, 100, 8, 0b110_100_100L)
    writeOctal(header, 108, 8, 0L)
    writeOctal(header, 116, 8, 0L)
    writeOctal(header, 124, 12, size.coerceAtMost(MAX_STANDARD_TAR_SIZE))
    writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L)
    for (i in 148 until 156) header[i] = ' '.code.toByte()
    header[156] = typeFlag
    writeAscii(header, 257, 6, "ustar")
    writeAscii(header, 263, 2, "00")
    val checksum = header.sumOf { it.toInt() and 0xFF }
    val checksumText = checksum.toString(8).padStart(6, '0')
    writeAscii(header, 148, 6, checksumText)
    header[154] = 0
    header[155] = ' '.code.toByte()
    return header
}

fun paxRecords(path: String, size: Long): ByteArray {
    return buildString {
        append(paxRecord("path", path))
        append(paxRecord("size", size.toString()))
    }.toByteArray(StandardCharsets.UTF_8)
}

private fun paxRecord(key: String, value: String): String {
    var lengthDigits = 1
    while (true) {
        val length = lengthDigits + 1 +
            key.toByteArray(StandardCharsets.UTF_8).size + 1 +
            value.toByteArray(StandardCharsets.UTF_8).size + 1
        val newLengthDigits = length.toString().length
        if (newLengthDigits == lengthDigits) {
            return "$length $key=$value\n"
        }
        lengthDigits = newLengthDigits
    }
}

private fun safeTarHeaderName(name: String): String {
    val ascii = name.map { if (it.code in 32..126 && it != '/') it else '_' }.joinToString("")
    val bytes = ascii.toByteArray(StandardCharsets.US_ASCII)
    if (bytes.size <= 100) return ascii
    return String(bytes.copyOfRange(0, 100), StandardCharsets.US_ASCII).trimEnd('_')
}

private fun writeAscii(target: ByteArray, offset: Int, length: Int, value: String) {
    val bytes = value.toByteArray(StandardCharsets.US_ASCII)
    val count = minOf(bytes.size, length)
    System.arraycopy(bytes, 0, target, offset, count)
}

private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
    val text = value.toString(8).takeLast(length - 1).padStart(length - 1, '0')
    writeAscii(target, offset, length - 1, text)
    target[offset + length - 1] = 0
}

fun Long.roundUpToTarBlock(): Long {
    val remainder = this % TAR_BLOCK_SIZE
    return if (remainder == 0L) this else checkedAdd(TAR_BLOCK_SIZE - remainder)
}

private fun Long.checkedAdd(other: Long): Long {
    require(other >= 0) { "negative size component" }
    require(this <= Long.MAX_VALUE - other) { "transfer size is too large" }
    return this + other
}

const val TAR_BLOCK_SIZE = 512
const val END_OF_ARCHIVE_BYTES = TAR_BLOCK_SIZE * 2L
private const val MAX_STANDARD_TAR_SIZE = 0x1FFFFFFFFL

package site.sexyminup.p2pfileshare.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

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

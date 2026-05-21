package site.sexyminup.p2pfileshare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun getConfig(baseUrl: String): ConfigResponse = get(baseUrl, "/api/config")

    suspend fun createRoom(
        baseUrl: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ): RoomCreateResponse = post(
        baseUrl = baseUrl,
        path = "/api/rooms",
        body = RoomCreateRequest(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            clientType = "android",
        ),
    )

    suspend fun joinRoom(baseUrl: String, code: String): RoomJoinResponse = post(
        baseUrl = baseUrl,
        path = "/api/rooms/join",
        body = RoomJoinRequest(code = code, clientType = "android"),
    )

    private suspend inline fun <reified T> get(baseUrl: String, path: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseError(text, response.code))
            }
            json.decodeFromString<T>(text)
        }
    }

    private suspend inline fun <reified T, reified B> post(baseUrl: String, path: String, body: B): T =
        withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(parseError(text, response.code))
                }
                json.decodeFromString<T>(text)
            }
        }

    private fun parseError(text: String, code: Int): String {
        return runCatching { json.decodeFromString<ApiError>(text).detail }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "서버 요청 실패: HTTP $code"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

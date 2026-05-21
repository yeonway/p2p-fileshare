package site.sexyminup.p2pfileshare.transfer

const val SAFE_DATA_CHANNEL_CHUNK_SIZE_BYTES = 65_536

fun resolveDataChannelChunkSize(configuredChunkSizeBytes: Int?): Int {
    val configured = configuredChunkSizeBytes?.takeIf { it > 0 } ?: SAFE_DATA_CHANNEL_CHUNK_SIZE_BYTES
    return configured.coerceAtMost(SAFE_DATA_CHANNEL_CHUNK_SIZE_BYTES)
}

package com.yulin.rider.core.update

internal data class ByteSpan(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal object RangePlan {
    const val DEFAULT_PARTS = 4
    const val MIN_PARALLEL_BYTES = 2L * 1024 * 1024

    fun spans(totalBytes: Long, partCount: Int = DEFAULT_PARTS): List<ByteSpan> {
        require(totalBytes > 0L) { "totalBytes must be positive" }
        val parts = partCount.coerceIn(1, 8)
        if (parts == 1) {
            return listOf(ByteSpan(0L, totalBytes - 1L))
        }
        val size = totalBytes / parts
        return (0 until parts).map { index ->
            val start = index * size
            val end = if (index == parts - 1) totalBytes - 1L else start + size - 1L
            ByteSpan(start, end)
        }
    }

    fun supportsParallel(acceptRanges: String?, totalBytes: Long): Boolean {
        if (totalBytes < MIN_PARALLEL_BYTES) return false
        return acceptRanges?.contains("bytes", ignoreCase = true) == true
    }
}

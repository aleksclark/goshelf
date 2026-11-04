package com.goshelf.app.data.download

/** Parsed `Content-Range: bytes start-end/total` header. */
data class ContentRange(val start: Long, val end: Long, val total: Long)

private val contentRangeRegex = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

fun parseContentRange(header: String?): ContentRange? {
    val match = contentRangeRegex.matchEntire(header ?: return null) ?: return null
    val (start, end, total) = match.destructured
    return ContentRange(
        start = start.toLong(),
        end = end.toLong(),
        total = if (total == "*") -1L else total.toLong()
    )
}

/**
 * Decides where to resume a download. Returns the byte offset to continue
 * from, or 0 when existing partial data must be discarded because it belongs
 * to a different ZIP (changed ETag/size), was written by an older format, or
 * is longer than the ZIP itself.
 */
fun planResume(saved: DownloadMetadata?, partLength: Long, etag: String, totalSize: Long): Long {
    if (saved == null || partLength <= 0L) return 0L
    if (saved.formatVersion < DownloadStore.FORMAT_VERSION) return 0L
    if (saved.etag.isEmpty() || saved.etag != etag) return 0L
    if (saved.totalSize != totalSize) return 0L
    if (partLength > totalSize) return 0L
    return partLength
}

sealed interface RangeOutcome {
    /** Server returned the requested bytes; append them at the current offset. */
    data object Append : RangeOutcome

    /** Server already has nothing more to send; the part file is complete. */
    data object Complete : RangeOutcome

    /**
     * The served ZIP is not the one the partial data belongs to (If-Range
     * mismatch, size change, or an unexpected range). Discard and re-plan.
     */
    data class ZipChanged(val reason: String) : RangeOutcome
}

/**
 * Validates a download.zip response against what we asked for. A resume is
 * only accepted when the server honours the exact range of the exact ZIP.
 */
fun classifyRangeResponse(
    code: Int,
    contentRange: ContentRange?,
    responseEtag: String?,
    contentLength: Long,
    requestedOffset: Long,
    expectedEtag: String,
    expectedTotal: Long
): RangeOutcome {
    if (responseEtag != null && responseEtag != expectedEtag) {
        return RangeOutcome.ZipChanged("etag changed")
    }
    return when (code) {
        206 -> when {
            contentRange == null -> RangeOutcome.ZipChanged("missing Content-Range")
            contentRange.start != requestedOffset -> RangeOutcome.ZipChanged("range starts at ${contentRange.start}, wanted $requestedOffset")
            contentRange.total >= 0 && contentRange.total != expectedTotal -> RangeOutcome.ZipChanged("size changed")
            else -> RangeOutcome.Append
        }
        200 -> when {
            requestedOffset > 0L -> RangeOutcome.ZipChanged("server ignored range")
            contentLength >= 0 && contentLength != expectedTotal -> RangeOutcome.ZipChanged("size changed")
            else -> RangeOutcome.Append
        }
        416 -> if (requestedOffset == expectedTotal && (contentRange == null || contentRange.total == expectedTotal)) {
            RangeOutcome.Complete
        } else {
            RangeOutcome.ZipChanged("range not satisfiable")
        }
        else -> RangeOutcome.ZipChanged("unexpected status $code")
    }
}

/** Exponential backoff capped at [maxMs]. [attempt] starts at 1. */
fun backoffDelayMs(attempt: Int, initialMs: Long, maxMs: Long): Long {
    if (attempt <= 1) return initialMs
    val shift = (attempt - 1).coerceAtMost(20)
    return (initialMs shl shift).coerceAtMost(maxMs)
}

/** Percent in 0..100, safe for zero totals. */
fun percentOf(done: Long, total: Long): Int {
    if (total <= 0L) return 0
    return ((done.coerceAtLeast(0L) * 100) / total).toInt().coerceIn(0, 100)
}

/** Bytes-per-second estimate refreshed at most once per [windowMs]. */
class SpeedMeter(startBytes: Long, startTimeMs: Long, private val windowMs: Long = 1000L) {
    private var lastBytes = startBytes
    private var lastTimeMs = startTimeMs
    var bytesPerSecond: Long = 0L
        private set

    fun update(bytes: Long, nowMs: Long): Long {
        val elapsed = nowMs - lastTimeMs
        if (elapsed >= windowMs) {
            bytesPerSecond = ((bytes - lastBytes).coerceAtLeast(0L) * 1000L) / elapsed
            lastBytes = bytes
            lastTimeMs = nowMs
        }
        return bytesPerSecond
    }
}

/** Folder name for a book in the user's download tree. */
fun bookFolderName(title: String, bookId: Int): String {
    val sanitized = title.replace(Regex("[^a-zA-Z0-9\\s\\-_.'()]"), "").trim()
    return sanitized.ifEmpty { "Book $bookId" }
}

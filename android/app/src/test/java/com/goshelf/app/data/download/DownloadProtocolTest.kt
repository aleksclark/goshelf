package com.goshelf.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProtocolTest {

    private val etag = "\"abc123\""
    private val total = 10_000L

    private fun saved(
        etag: String = this.etag,
        totalSize: Long = total,
        formatVersion: Int = DownloadStore.FORMAT_VERSION
    ) = DownloadMetadata(
        bookId = 1,
        bookTitle = "Book",
        etag = etag,
        totalSize = totalSize,
        bytesDownloaded = 0,
        outputDirUri = "content://tree",
        tempFile = "/tmp/book_1.part",
        formatVersion = formatVersion
    )

    @Test
    fun parseContentRange_parsesStandardHeader() {
        assertEquals(ContentRange(100, 9999, 10000), parseContentRange("bytes 100-9999/10000"))
        assertEquals(ContentRange(0, 0, -1), parseContentRange("bytes 0-0/*"))
    }

    @Test
    fun parseContentRange_rejectsGarbage() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes */10000"))
        assertNull(parseContentRange("items 1-2/3"))
    }

    @Test
    fun planResume_resumesMatchingPartial() {
        assertEquals(4096L, planResume(saved(), 4096L, etag, total))
    }

    @Test
    fun planResume_restartsWhenEtagOrSizeChanged() {
        assertEquals(0L, planResume(saved(etag = "\"old\""), 4096L, etag, total))
        assertEquals(0L, planResume(saved(totalSize = total + 1), 4096L, etag, total))
        assertEquals(0L, planResume(saved(etag = ""), 4096L, "", total))
    }

    @Test
    fun planResume_restartsForOldFormatMissingOrOversizedPartial() {
        assertEquals(0L, planResume(saved(formatVersion = 0), 4096L, etag, total))
        assertEquals(0L, planResume(null, 4096L, etag, total))
        assertEquals(0L, planResume(saved(), 0L, etag, total))
        assertEquals(0L, planResume(saved(), total + 1, etag, total))
    }

    @Test
    fun planResume_keepsCompletePartial() {
        assertEquals(total, planResume(saved(), total, etag, total))
    }

    @Test
    fun classify_acceptsExactPartialContent() {
        val outcome = classifyRangeResponse(
            code = 206,
            contentRange = ContentRange(4096, total - 1, total),
            responseEtag = etag,
            contentLength = total - 4096,
            requestedOffset = 4096,
            expectedEtag = etag,
            expectedTotal = total
        )
        assertEquals(RangeOutcome.Append, outcome)
    }

    @Test
    fun classify_rejectsWrongRangeStartOrSize() {
        val wrongStart = classifyRangeResponse(206, ContentRange(0, total - 1, total), etag, total, 4096, etag, total)
        assertTrue(wrongStart is RangeOutcome.ZipChanged)

        val wrongTotal = classifyRangeResponse(206, ContentRange(4096, total, total + 1), etag, total - 4095, 4096, etag, total)
        assertTrue(wrongTotal is RangeOutcome.ZipChanged)

        val missingRange = classifyRangeResponse(206, null, etag, total - 4096, 4096, etag, total)
        assertTrue(missingRange is RangeOutcome.ZipChanged)
    }

    @Test
    fun classify_fullResponseToResumeMeansZipChanged() {
        val outcome = classifyRangeResponse(200, null, "\"new\"", total, 4096, etag, total)
        assertTrue(outcome is RangeOutcome.ZipChanged)

        val ignoredRange = classifyRangeResponse(200, null, etag, total, 4096, etag, total)
        assertTrue(ignoredRange is RangeOutcome.ZipChanged)
    }

    @Test
    fun classify_freshDownload() {
        assertEquals(RangeOutcome.Append, classifyRangeResponse(200, null, etag, total, 0, etag, total))
        assertEquals(RangeOutcome.Append, classifyRangeResponse(200, null, null, -1, 0, etag, total))
        assertTrue(classifyRangeResponse(200, null, etag, total - 1, 0, etag, total) is RangeOutcome.ZipChanged)
    }

    @Test
    fun classify_unsatisfiableRangeAtEndIsComplete() {
        val done = classifyRangeResponse(416, parseContentRange("bytes */10000"), null, 0, total, etag, total)
        assertEquals(RangeOutcome.Complete, done)

        val notDone = classifyRangeResponse(416, null, null, 0, 4096, etag, total)
        assertTrue(notDone is RangeOutcome.ZipChanged)
    }

    @Test
    fun backoff_growsAndCaps() {
        assertEquals(2_000L, backoffDelayMs(1, 2_000L, 60_000L))
        assertEquals(4_000L, backoffDelayMs(2, 2_000L, 60_000L))
        assertEquals(32_000L, backoffDelayMs(5, 2_000L, 60_000L))
        assertEquals(60_000L, backoffDelayMs(6, 2_000L, 60_000L))
        assertEquals(60_000L, backoffDelayMs(100, 2_000L, 60_000L))
    }

    @Test
    fun percentOf_handlesEdges() {
        assertEquals(0, percentOf(10, 0))
        assertEquals(50, percentOf(5, 10))
        assertEquals(100, percentOf(20, 10))
        assertEquals(0, percentOf(-1, 10))
    }

    @Test
    fun speedMeter_updatesOncePerWindow() {
        val meter = SpeedMeter(0, 0)
        assertEquals(0L, meter.update(500, 500))
        assertEquals(2_000L, meter.update(2_000, 1_000))
        assertEquals(2_000L, meter.update(3_000, 1_500))
        assertEquals(2_000L, meter.update(4_000, 2_000))
    }

    @Test
    fun bookFolderName_sanitizesAndFallsBack() {
        assertEquals("Dune Messiah (Book 2)", bookFolderName("Dune: Messiah (Book 2)", 7))
        assertEquals("Book 7", bookFolderName("???", 7))
    }
}

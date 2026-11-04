package com.goshelf.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveLoadAndClear() {
        val store = DownloadStore(tmp.root)
        val metadata = DownloadMetadata(
            bookId = 42,
            bookTitle = "Book",
            etag = "\"e\"",
            totalSize = 100,
            bytesDownloaded = 10,
            outputDirUri = "content://tree",
            tempFile = store.partFile(42).absolutePath
        )
        store.save(metadata)
        store.partFile(42).writeBytes(ByteArray(10))

        assertEquals(metadata, store.load(42))
        assertTrue(store.hasPartial(42))
        assertEquals(listOf(42), store.pendingBookIds())

        store.clear(42)
        assertFalse(store.hasPartial(42))
        assertEquals(null, store.load(42))
    }

    @Test
    fun metadataFromOlderAppVersionIsNotResumable() {
        val store = DownloadStore(tmp.root)
        store.metadataFile(7).writeText(
            """{"bookId":7,"bookTitle":"Old","etag":"\"e\"","totalSize":100,"bytesDownloaded":50,"outputDirUri":"x","tempFile":"y"}"""
        )
        val loaded = store.load(7)
        assertNotNull(loaded)
        assertEquals(0, loaded!!.formatVersion)
        assertEquals(0L, planResume(loaded, 50, "\"e\"", 100))
    }
}

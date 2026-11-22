package com.footballmodel.app

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedCacheStoreTest {
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory("iq-match-feed-cache").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun writesAndReadsKnownFeedBodies() {
        val body = """{"updatedAt":"2026-11-23","matches":[]}"""

        assertTrue(FeedCacheStore.write(directory, "domestic_json", body))
        assertEquals(body, FeedCacheStore.read(directory, "domestic_json"))
    }

    @Test fun replacesAnExistingFeedWithoutReturningTheOldBody() {
        assertTrue(FeedCacheStore.write(directory, "merged_json", "old"))
        assertTrue(FeedCacheStore.write(directory, "merged_json", "new"))

        assertEquals("new", FeedCacheStore.read(directory, "merged_json"))
    }

    @Test fun rejectsUnknownKeysAndDoesNotCreateArbitraryFiles() {
        assertFalse(FeedCacheStore.write(directory, "../outside", "payload"))
        assertNull(FeedCacheStore.read(directory, "../outside"))
        assertFalse(File(directory, "outside").exists())
    }

    @Test fun deleteIsIdempotent() {
        assertTrue(FeedCacheStore.delete(directory, "uefa_json"))
        assertTrue(FeedCacheStore.write(directory, "uefa_json", "body"))
        assertTrue(FeedCacheStore.delete(directory, "uefa_json"))
        assertNull(FeedCacheStore.read(directory, "uefa_json"))
    }
}

package com.gios.lightcamera.media

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roll's MediaStore query contract, checked without a ContentResolver.
 *
 * The query is the one piece of the roll you cannot look at on a phone: a row a query misses, or
 * a selection whose placeholders do not match its bound args, throws no exception — the gallery
 * just silently lacks an entry. These pin the filter contract so the next edit to the SQL has a
 * net to fall into.
 */
class MediaStoreRepoTest {

    private val scopes = RollScope.values()

    @Test
    fun `no scope shows in-flight rows`() {
        // The column name resolves through the same constant the query uses, so this can't
        // silently drift into asserting on a stale literal.
        val done = "${MediaStore.MediaColumns.IS_PENDING} = 0"
        for (scope in scopes) {
            assertTrue(
                "${scope.label} must exclude in-flight rows",
                MediaStoreRepo.rollSelection(scope).contains(done),
            )
        }
    }

    @Test
    fun `every placeholder has a bound arg`() {
        for (scope in scopes) {
            val selection = MediaStoreRepo.rollSelection(scope)
            assertEquals(
                "${scope.label}: ${selection.count { it == '?' }} placeholders, " +
                    "${MediaStoreRepo.rollArgs(scope).size} args",
                selection.count { it == '?' },
                MediaStoreRepo.rollArgs(scope).size,
            )
        }
    }

    @Test
    fun `the camera scope narrows to the camera folder`() {
        assertTrue(MediaStoreRepo.rollSelection(RollScope.Camera).contains("LIKE ?"))
    }
}

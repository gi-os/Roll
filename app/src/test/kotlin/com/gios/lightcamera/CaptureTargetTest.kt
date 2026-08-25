package com.gios.lightcamera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes of `EXTRA_OUTPUT` that an `IMAGE_CAPTURE` caller is not allowed to name.
 *
 * Roll opens the output stream with its own identity, so anything this lets through is
 * something an app holding no permission at all can have Roll write over. The live half of the
 * check needs a caller on the other end of a binder call; this is the half that does not.
 */
class CaptureTargetTest {

    private val us = "com.gios.lightcamera"

    private fun ok(scheme: String?, authority: String?) =
        CaptureTarget.isWritableTarget(scheme, authority, us)

    @Test
    fun `a foreign content authority is accepted`() {
        assertTrue(ok("content", "media"))
        assertTrue(ok("content", "com.gios.lightnotebook.files"))
        assertTrue(ok("content", "com.android.providers.downloads.documents"))
    }

    @Test
    fun `a file uri is refused`() {
        // The case the check exists for: a path is not a grant. /data/data/... is only
        // reachable because Roll is the one opening it.
        assertFalse(ok("file", null))
        assertFalse(ok("file", ""))
    }

    @Test
    fun `anything that is not content is refused`() {
        assertFalse(ok(null, "media"))
        assertFalse(ok("", "media"))
        assertFalse(ok("android.resource", "com.gios.lightnotebook"))
        assertFalse(ok("http", "example.com"))
        // Scheme comparison is exact rather than case-insensitive on purpose: Uri.getScheme()
        // is already lowercased by the parser, so a "CONTENT" here came from somewhere else.
        assertFalse(ok("CONTENT", "media"))
    }

    @Test
    fun `a content uri with no authority is refused`() {
        assertFalse(ok("content", null))
        assertFalse(ok("content", ""))
    }

    @Test
    fun `our own providers are refused`() {
        // Both are declared as ${applicationId} plus a suffix, so both start with the package
        // name. A caller pointing a capture at either is asking Roll to overwrite its own files.
        assertFalse(ok("content", "com.gios.lightcamera.stars"))
        assertFalse(ok("content", "com.gios.lightcamera.lightsync.backup"))
        assertFalse(ok("content", "com.gios.lightcamera"))
    }

    @Test
    fun `the prefix test is broad, deliberately`() {
        // A provider added under the application id later is covered without anyone having to
        // remember this file exists.
        assertFalse(ok("content", "com.gios.lightcamera.something.invented.next.year"))
        // The price is that an unrelated app whose id merely starts with ours is refused too.
        // That is the side to be wrong on: refusing it costs one caller a capture, and letting
        // it through costs Roll its own storage.
        assertFalse(ok("content", "com.gios.lightcameraroll.files"))
    }
}

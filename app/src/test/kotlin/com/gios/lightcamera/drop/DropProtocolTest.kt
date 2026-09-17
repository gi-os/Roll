package com.gios.lightcamera.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's string handling, held still.
 *
 * These are the failures that would not throw: a range parsed one byte out plays a clip and
 * refuses to scrub, a path that is not a number reaching a file lookup, a filename with a quote
 * in it splitting a response header. None of them need a socket to be wrong, so none of them need
 * one to be checked.
 */
class DropProtocolTest {

    /* ---------------- the head ---------------- */

    @Test
    fun `reads a request line and its headers`() {
        val request = DropProtocol.parseHead(
            "GET /api/items HTTP/1.1\r\nHost: 192.168.1.5:8088\r\nCookie: roll_drop=abc\r\n\r\n",
        )
        requireNotNull(request)
        assertEquals("GET", request.method)
        assertEquals("/api/items", request.path)
        assertEquals("192.168.1.5:8088", request.header("host"))
    }

    @Test
    fun `header lookup ignores case, because clients disagree about it`() {
        val request = DropProtocol.parseHead("GET / HTTP/1.1\r\nRANGE: bytes=0-1\r\n\r\n")
        assertEquals("bytes=0-1", request?.header("Range"))
    }

    @Test
    fun `nonsense on the wire is not a request`() {
        assertNull(DropProtocol.parseHead(""))
        assertNull(DropProtocol.parseHead("hello\r\n\r\n"))
    }

    @Test
    fun `a query string is split off the path`() {
        val (path, query) = DropProtocol.splitTarget("/file/42?dl=1&x=hello+world")
        assertEquals("/file/42", path)
        assertEquals("1", query["dl"])
        assertEquals("hello world", query["x"])
    }

    @Test
    fun `a broken percent escape is left alone rather than throwing`() {
        // URLDecoder would throw on both of these, and a scanner hitting the port must not be
        // able to take a worker thread down.
        assertEquals("100%", DropProtocol.percentDecode("100%"))
        assertEquals("%zz", DropProtocol.percentDecode("%zz"))
        assertEquals("a b", DropProtocol.percentDecode("a%20b"))
    }

    @Test
    fun `one cookie is found among several`() {
        assertEquals("xyz", DropProtocol.cookie("other=1; roll_drop=xyz; third=2", "roll_drop"))
        assertNull(DropProtocol.cookie("other=1", "roll_drop"))
        assertNull(DropProtocol.cookie(null, "roll_drop"))
    }

    /* ---------------- routing ---------------- */

    @Test
    fun `routes are ids, never paths`() {
        assertEquals(DropProtocol.Route.Page, DropProtocol.route("/"))
        assertEquals(DropProtocol.Route.Items, DropProtocol.route("/api/items"))
        assertEquals(DropProtocol.Route.Thumb(42L), DropProtocol.route("/thumb/42"))
        assertEquals(DropProtocol.Route.File(7L), DropProtocol.route("/file/7"))
    }

    @Test
    fun `traversal is not a route because it is not a number`() {
        assertEquals(DropProtocol.Route.NotFound, DropProtocol.route("/file/../../etc/passwd"))
        assertEquals(DropProtocol.Route.NotFound, DropProtocol.route("/file/"))
        assertEquals(DropProtocol.Route.NotFound, DropProtocol.route("/thumb/1.jpg"))
        assertEquals(DropProtocol.Route.NotFound, DropProtocol.route("/etc/passwd"))
    }

    /* ---------------- ranges ---------------- */

    @Test
    fun `no range header means the whole file`() {
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span(null, 1000))
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span("", 1000))
    }

    @Test
    fun `a closed range is inclusive at both ends`() {
        val span = DropProtocol.span("bytes=100-199", 1000) as DropProtocol.Span.Part
        assertEquals(100L, span.from)
        assertEquals(199L, span.to)
        assertEquals(100L, span.length)
    }

    @Test
    fun `an open range runs to the last byte`() {
        val span = DropProtocol.span("bytes=500-", 1000) as DropProtocol.Span.Part
        assertEquals(500L, span.from)
        assertEquals(999L, span.to)
        assertEquals(500L, span.length)
    }

    @Test
    fun `a suffix range is the tail, which is where an mp4 keeps its index`() {
        val span = DropProtocol.span("bytes=-200", 1000) as DropProtocol.Span.Part
        assertEquals(800L, span.from)
        assertEquals(999L, span.to)
    }

    @Test
    fun `a suffix longer than the file is the whole file`() {
        val span = DropProtocol.span("bytes=-5000", 1000) as DropProtocol.Span.Part
        assertEquals(0L, span.from)
        assertEquals(999L, span.to)
    }

    @Test
    fun `an end past the file is clamped rather than refused`() {
        val span = DropProtocol.span("bytes=900-99999", 1000) as DropProtocol.Span.Part
        assertEquals(900L, span.from)
        assertEquals(999L, span.to)
    }

    @Test
    fun `a start past the end cannot be satisfied`() {
        assertEquals(DropProtocol.Span.Unsatisfiable, DropProtocol.span("bytes=1000-", 1000))
        assertEquals(DropProtocol.Span.Unsatisfiable, DropProtocol.span("bytes=500-100", 1000))
    }

    @Test
    fun `Safari's probe for range support is a two byte part, not the whole file`() {
        // Safari opens with `bytes=0-1`. Answer it with a 200 and the whole file and it decides
        // the server does not do ranges, after which the scrub bar does nothing.
        val span = DropProtocol.span("bytes=0-1", 50_000_000) as DropProtocol.Span.Part
        assertEquals(0L, span.from)
        assertEquals(1L, span.to)
        assertEquals(2L, span.length)
    }

    @Test
    fun `a multi-range request is answered whole rather than badly`() {
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span("bytes=0-50,100-150", 1000))
    }

    @Test
    fun `junk in a range header does not throw`() {
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span("bytes=abc-def", 1000))
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span("items=0-1", 1000))
        assertEquals(DropProtocol.Span.Whole, DropProtocol.span("bytes=0-1", 0))
    }

    /* ---------------- secrets ---------------- */

    @Test
    fun `a token comparison needs every character`() {
        assertTrue(DropProtocol.constantTimeEquals("abc123", "abc123"))
        assertFalse(DropProtocol.constantTimeEquals("abc123", "abc124"))
        assertFalse(DropProtocol.constantTimeEquals("abc", "abc123"))
        assertFalse(DropProtocol.constantTimeEquals(null, "abc"))
        assertFalse(DropProtocol.constantTimeEquals("abc", null))
        // The empty-token case is the one that matters: a stopped server has no token, and an
        // empty cookie must not match it.
        assertFalse(DropProtocol.constantTimeEquals("", null))
    }

    /* ---------------- what goes out ---------------- */

    @Test
    fun `a filename cannot close the string it is written into`() {
        assertEquals("\"a\\\"b\\n.jpg\"", DropProtocol.jsonString("a\"b\n.jpg"))
        // `<` is escaped so a photograph named `</script>` cannot close the tag its name is
        // being written into.
        assertEquals("\"\\u003c/script>\"", DropProtocol.jsonString("</script>"))
    }

    @Test
    fun `an item carries every format the one press wrote`() {
        val json = DropProtocol.jsonItem(
            id = 9,
            name = "ROLL_20260917_101500_123.jpg",
            takenAt = 1_700_000_000_000L,
            width = 4000,
            height = 3000,
            isVideo = false,
            durationMs = 0,
            formats = listOf("JPG" to 9L, "RAW" to 10L),
        )
        assertTrue(json.startsWith("{\"id\":9,"))
        assertTrue(json.contains("\"video\":false"))
        assertTrue(json.contains("{\"label\":\"JPG\",\"id\":9}"))
        assertTrue(json.contains("{\"label\":\"RAW\",\"id\":10}"))
    }

    @Test
    fun `a content-disposition filename cannot split the response`() {
        assertEquals("ROLL_20260917.jpg", DropProtocol.safeFilename("ROLL_20260917.jpg"))
        // A quote or a CRLF in this header is a response-splitting hole, and MediaStore will
        // hand back whatever the display name says.
        assertFalse(DropProtocol.safeFilename("a\"b\r\nX-Evil: 1").contains('"'))
        assertFalse(DropProtocol.safeFilename("a\"b\r\nX-Evil: 1").contains('\r'))
        assertFalse(DropProtocol.safeFilename("a\"b\r\nX-Evil: 1").contains('\n'))
        assertEquals("photo", DropProtocol.safeFilename("///"))
    }
}

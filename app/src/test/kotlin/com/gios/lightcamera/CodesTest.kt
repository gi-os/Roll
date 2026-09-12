package com.gios.lightcamera

import com.gios.lightcamera.qr.Codes
import com.gios.lightcamera.qr.ScanGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodesTest {

    /* ---------------- what it is ---------------- */

    @Test
    fun `urls are links`() {
        assertEquals(Codes.Kind.Link, Codes.kindOf("https://example.com/a?b=c"))
        assertEquals(Codes.Kind.Link, Codes.kindOf("HTTP://Example.com"))
    }

    @Test
    fun `bare domains are links and complete to https`() {
        assertEquals(Codes.Kind.Link, Codes.kindOf("example.com"))
        assertEquals("https://example.com", Codes.openable("example.com"))
        assertEquals("https://a.co.uk/x", Codes.openable("a.co.uk/x"))
    }

    /**
     * The reason this file exists rather than calling `Patterns.WEB_URL`: that matcher takes all
     * three of these as web addresses, so a code carrying a version number offered to browse to it.
     */
    @Test
    fun `things shaped like numbers are not domains`() {
        assertFalse(Codes.isBareDomain("1.2"))
        assertFalse(Codes.isBareDomain("v2.0"))
        assertFalse(Codes.isBareDomain("roll.1"))
        assertEquals(Codes.Kind.Text, Codes.kindOf("1.2"))
        assertNull(Codes.openable("build 1.2"))
    }

    @Test
    fun `schemes outside the allow-list never open`() {
        assertNull(Codes.openable("intent://evil#Intent;scheme=http;end"))
        assertNull(Codes.openable("market://details?id=com.example"))
        assertNull(Codes.openable("file:///sdcard/x"))
        assertNull(Codes.openable("javascript:alert(1)"))
        assertNull(Codes.openable("otpauth://totp/x?secret=y"))
    }

    @Test
    fun `the schemes on the list pass through`() {
        assertEquals("tel:+15551234", Codes.openable("tel:+15551234"))
        assertEquals("mailto:a@b.com", Codes.openable("mailto:a@b.com"))
        assertEquals("geo:40.7,-74.0", Codes.openable("geo:40.7,-74.0"))
    }

    /** Both spellings are printed; Android only understands the second. */
    @Test
    fun `sms is normalised to smsto`() {
        assertEquals("smsto:5551234", Codes.openable("sms:5551234"))
        assertEquals("smsto:5551234", Codes.openable("SMSTO:5551234"))
    }

    /* ---------------- titles ---------------- */

    @Test
    fun `a link is titled by its host`() {
        assertEquals("example.com", Codes.title("https://example.com/very/long/path?utm=nonsense"))
        assertEquals("example.com", Codes.title("example.com/x"))
        assertEquals("example.com", Codes.host("https://user@example.com:8443/x"))
    }

    @Test
    fun `a vcard is titled by its name`() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nN:Lupo;Giovanni\nFN:Giovanni Lupo\nEND:VCARD"
        assertEquals(Codes.Kind.Contact, Codes.kindOf(card))
        assertEquals("Giovanni Lupo", Codes.title(card))
        // FN missing: fall back to the structured name, family last.
        val bare = "BEGIN:VCARD\nN:Lupo;Giovanni\nEND:VCARD"
        assertEquals("Giovanni Lupo", Codes.title(bare))
    }

    @Test
    fun `text falls back to its first line`() {
        assertEquals("hello there", Codes.title("hello there\nsecond line"))
        assertEquals(Codes.Kind.Text, Codes.kindOf("hello there"))
    }

    /* ---------------- wi-fi ---------------- */

    @Test
    fun `wifi fields are parsed`() {
        val w = Codes.wifi("WIFI:S:BasilNet;T:WPA;P:hunter2;H:true;;")!!
        assertEquals("BasilNet", w.ssid)
        assertEquals("hunter2", w.password)
        assertEquals("WPA", w.security)
        assertTrue(w.hidden)
        assertNull(Codes.openable("WIFI:S:BasilNet;T:WPA;P:hunter2;;"))
    }

    /** The case a `split(';')` cuts in half, which is also the case people actually hit. */
    @Test
    fun `escaped separators survive inside a password`() {
        val w = Codes.wifi("""WIFI:S:Cafe\;1;T:WPA;P:pa\;ss\:word;;""")!!
        assertEquals("Cafe;1", w.ssid)
        assertEquals("pa;ss:word", w.password)
    }

    @Test
    fun `a wifi payload with no ssid is not a network`() {
        assertNull(Codes.wifi("WIFI:T:WPA;P:x;;"))
        assertNull(Codes.wifi("https://example.com"))
    }

    @Test
    fun `security defaults to nopass when the field is absent`() {
        assertEquals("nopass", Codes.wifi("WIFI:S:Open;;")!!.security)
    }

    /* ---------------- the gate ---------------- */

    @Test
    fun `the first read of a code counts`() {
        val gate = ScanGate(repeatMs = 2_000)
        assertTrue(gate.accept("a", 0))
    }

    @Test
    fun `a code held in frame never fires twice`() {
        val gate = ScanGate(repeatMs = 2_000)
        assertTrue(gate.accept("a", 0))
        // Twenty reads a second for ten seconds. The window slides on every one of them, so a
        // fixed-window implementation would have fired four more times through here.
        var t = 0L
        repeat(200) {
            t += 50
            assertFalse("re-fired at ${'$'}t ms", gate.accept("a", t))
        }
    }

    @Test
    fun `the same code fires again after it has been away`() {
        val gate = ScanGate(repeatMs = 2_000)
        assertTrue(gate.accept("a", 0))
        assertFalse(gate.accept("a", 500))
        assertTrue(gate.accept("a", 3_000))
    }

    @Test
    fun `a different code is news immediately`() {
        val gate = ScanGate(repeatMs = 2_000)
        assertTrue(gate.accept("a", 0))
        assertTrue(gate.accept("b", 10))
        assertFalse(gate.accept("b", 20))
        assertTrue(gate.accept("a", 30))
    }

    @Test
    fun `blank reads are never news`() {
        val gate = ScanGate()
        assertFalse(gate.accept("", 0))
        assertFalse(gate.accept("   ", 0))
    }

    @Test
    fun `reset makes the next read count`() {
        val gate = ScanGate(repeatMs = 10_000)
        assertTrue(gate.accept("a", 0))
        assertFalse(gate.accept("a", 100))
        gate.reset()
        assertTrue(gate.accept("a", 200))
    }

    /* ---------------- a Web Tools code ---------------- */

    private val toolCode =
        """{"wt":1,"n":"Tickets","u":"https://www.ticketmaster.com/","o":["ticketmaster.com"],"keep":true,"f":"Tickets"}"""

    @Test
    fun `a web tools code is its own kind, not text`() {
        assertEquals(Codes.Kind.Tool, Codes.kindOf(toolCode))
        assertTrue(Codes.isWebTool(toolCode))
        assertEquals("WEB TOOL", Codes.heading(Codes.Kind.Tool))
    }

    /** The sheet leads with the name the companion page gave it. */
    @Test
    fun `a web tools code is titled by its name`() {
        assertEquals("Tickets", Codes.title(toolCode))
    }

    /** No name in the code: the host it opens says as much as anything would. */
    @Test
    fun `a nameless web tools code falls back to the host`() {
        assertEquals("axs.com", Codes.title("""{"wt":1,"u":"https://axs.com/events?x=1"}"""))
        assertEquals("Web tool", Codes.title("""{"wt":1}"""))
    }

    /**
     * A code too big for one image arrives in frames, and a frame that says only "part 2 of 4" is
     * the one case where the number *is* the whole message.
     */
    @Test
    fun `a part frame says which part it is`() {
        val part = """{"wt":1,"k":"part","id":"x7","i":2,"n":4,"p":"eJxLy"}"""
        assertEquals(Codes.Kind.Tool, Codes.kindOf(part))
        assertEquals("Part 2 of 4", Codes.title(part))
    }

    /**
     * `n` is the tool's name in one shape of this payload and the number of parts in the other, so
     * a bare number loses to the host rather than becoming the title of a tool called "4".
     */
    @Test
    fun `a number is not taken as a name`() {
        assertEquals("x.example.com", Codes.title("""{"wt":1,"n":4,"u":"https://x.example.com/"}"""))
        assertEquals("4 Tickets", Codes.title("""{"wt":1,"n":"4 Tickets","u":"https://x.example.com/"}"""))
    }

    /**
     * The envelope is the whole test. Anything that is not a `wt` object stays what it was, which
     * is what stops an ordinary JSON payload on a poster from offering to install itself.
     */
    @Test
    fun `other json is still text`() {
        assertEquals(Codes.Kind.Text, Codes.kindOf("""{"id":7,"name":"Ada"}"""))
        assertFalse(Codes.isWebTool("""{"wt":"yes"}"""))
        assertFalse(Codes.isWebTool("""["wt",1]"""))
        assertFalse(Codes.isWebTool("https://example.com/?wt=1"))
    }

    /** A code Web Tools will refuse is still recognised here — it gets Web Tools' reason, not ours. */
    @Test
    fun `a broken web tools code is still a web tools code`() {
        assertEquals(Codes.Kind.Tool, Codes.kindOf("""{"wt":1,"u":"not a url"""))
    }

    /** There is nothing to launch: the sheet's verb is "add", and that is decided by the kind. */
    @Test
    fun `a web tools code opens nothing by itself`() {
        assertNull(Codes.openable(toolCode))
    }

    /** Escapes inside a name are resolved rather than shown. */
    @Test
    fun `a quoted name reads back whole`() {
        assertEquals("Ada\"s \\ tickets", Codes.title("""{"wt":1,"n":"Ada\"s \\ tickets"}"""))
        assertEquals("café", Codes.title("""{"wt":1,"n":"café"}"""))
    }
}

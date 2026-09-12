package com.gios.lightcamera.qr

/**
 * What is *in* a QR code, worked out from the payload alone.
 *
 * **No Android imports anywhere in this file, deliberately.** Everything here is string work with
 * exactly one right answer per input, which makes it the part of QR mode that can be tested on the
 * JVM rather than by holding the phone up to a poster. LightQR used `android.util.Patterns.WEB_URL`
 * for the same job and paid for it: that matcher accepts `1.2` and `a.b` as web addresses, so a code
 * containing a version number offered to open `https://1.2` in a browser.
 *
 * The parsing follows the ZXing "barcode contents" conventions, which are what every generator in
 * the world actually emits — `WIFI:`, `MECARD:`, `BEGIN:VCARD`, `geo:`, `SMSTO:`.
 */
object Codes {

    enum class Kind { Link, Wifi, Contact, Phone, Email, Sms, Place, Tool, Text }

    /** The word at the top of the sheet. */
    fun heading(kind: Kind): String = when (kind) {
        Kind.Link -> "LINK"
        Kind.Wifi -> "WI-FI"
        Kind.Contact -> "CONTACT"
        Kind.Phone -> "PHONE"
        Kind.Email -> "EMAIL"
        Kind.Sms -> "MESSAGE"
        Kind.Place -> "PLACE"
        Kind.Tool -> "WEB TOOL"
        Kind.Text -> "TEXT"
    }

    fun kindOf(raw: String): Kind {
        val t = raw.trim()
        val lower = t.lowercase()
        return when {
            isWebTool(t) -> Kind.Tool
            lower.startsWith("wifi:") -> Kind.Wifi
            lower.startsWith("begin:vcard") || lower.startsWith("mecard:") -> Kind.Contact
            lower.startsWith("tel:") -> Kind.Phone
            lower.startsWith("mailto:") -> Kind.Email
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> Kind.Sms
            lower.startsWith("geo:") -> Kind.Place
            lower.startsWith("http://") || lower.startsWith("https://") -> Kind.Link
            isBareDomain(t) -> Kind.Link
            else -> Kind.Text
        }
    }

    /**
     * The one line worth reading first — a host, an SSID, a name, a number.
     *
     * The screen is 3.92" and the payload can be two thousand characters, so the sheet leads with
     * the part that tells you whether you want this at all, and shows the rest underneath.
     */
    fun title(raw: String): String {
        val t = raw.trim()
        return when (kindOf(t)) {
            Kind.Link -> host(openable(t) ?: t) ?: t
            Kind.Wifi -> wifi(t)?.ssid ?: "Network"
            Kind.Contact -> contactName(t) ?: "Card"
            Kind.Phone -> t.removePrefix("tel:").removePrefix("TEL:")
            Kind.Email -> t.substringAfter(':').substringBefore('?')
            Kind.Sms -> t.substringAfter(':').substringBefore(':').substringBefore('?')
            Kind.Place -> t.removePrefix("geo:").substringBefore('?')
            Kind.Tool -> toolTitle(t)
            Kind.Text -> t.lineSequence().firstOrNull()?.take(80).orEmpty()
        }.ifBlank { t.take(80) }
    }

    /**
     * A URI the phone can be handed, or null when there is nothing to open.
     *
     * Null is a real answer and not a failure: a Wi-Fi credential, a vCard and a paragraph of text
     * are all things you copy, not things you launch. The sheet drops its OPEN row when this is
     * null rather than offering a button that would apologise.
     *
     * The scheme allow-list is the whole security story here. A QR code is an untrusted string a
     * stranger printed on a wall, and `startActivity` on an arbitrary scheme is how a poster gets to
     * poke at whatever `intent:` or `market:` or vendor-private handler is installed. Only the seven
     * schemes below are ever handed on, and everything else — including anything shaped like an
     * Android intent URI — is treated as text.
     */
    fun openable(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val scheme = schemeOf(t)
        if (scheme != null) {
            return when (scheme) {
                "http", "https", "tel", "mailto", "geo" -> t
                // Both spellings exist in the wild; Android understands the second.
                "sms", "smsto" -> "smsto:" + t.substringAfter(':')
                else -> null
            }
        }
        // No scheme at all. A bare host is the one case worth completing, because printed codes
        // very often carry `example.com` and nothing else.
        return if (isBareDomain(t)) "https://$t" else null
    }

    /**
     * The URI scheme at the front of a payload, lowercased, or null if there isn't one.
     *
     * RFC 3986's rule and not `substringBefore(':')`, which was the first version and was wrong on
     * `example.com/a:b` — it read the whole path as a scheme, found it on no allow-list, and refused
     * to open a perfectly ordinary link. A scheme starts with a letter, contains only letters,
     * digits, `+`, `-` and `.`, and cannot contain a slash.
     */
    private fun schemeOf(text: String): String? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val candidate = text.substring(0, colon)
        if (!candidate[0].isLetter()) return null
        if (candidate.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '+' && it != '-' && it != '.' }) {
            return null
        }
        return candidate.lowercase()
    }

    /** The credentials out of a `WIFI:S:name;T:WPA;P:secret;;` payload. */
    data class Wifi(val ssid: String, val password: String, val security: String, val hidden: Boolean)

    fun wifi(raw: String): Wifi? {
        val t = raw.trim()
        if (!t.lowercase().startsWith("wifi:")) return null
        val body = t.substring(5)
        val fields = HashMap<String, String>()
        // Fields are `K:value;` and a literal `;`, `:`, `\` or `,` inside a value is backslash
        // escaped — which matters, because a Wi-Fi password containing a semicolon is exactly the
        // kind of password people use and a naive `split(';')` cuts it in half.
        var key: String? = null
        val token = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { token.append(body[i + 1]); i++ }
                c == ':' && key == null -> { key = token.toString(); token.setLength(0) }
                c == ';' -> {
                    key?.let { fields[it.uppercase()] = token.toString() }
                    key = null
                    token.setLength(0)
                }
                else -> token.append(c)
            }
            i++
        }
        val ssid = fields["S"].orEmpty()
        if (ssid.isEmpty()) return null
        return Wifi(
            ssid = ssid,
            password = fields["P"].orEmpty(),
            security = fields["T"].orEmpty().ifEmpty { "nopass" },
            hidden = fields["H"].equals("true", ignoreCase = true),
        )
    }

    /** The host of a URL, for the sheet's title. Null when it doesn't parse as one. */
    fun host(url: String): String? {
        val after = url.substringAfter("://", "")
        if (after.isEmpty()) return null
        val authority = after.substringBefore('/').substringBefore('?').substringBefore('#')
        val h = authority.substringAfterLast('@').substringBefore(':')
        return h.ifEmpty { null }
    }

    private fun contactName(raw: String): String? {
        val lower = raw.lowercase()
        if (lower.startsWith("mecard:")) {
            return raw.substringAfter("N:", "").substringBefore(';').ifBlank { null }
        }
        // vCard: FN is the display name, N is the structured one with `;` separators.
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.startsWith("FN:", ignoreCase = true)) return l.substring(3).trim().ifBlank { null }
        }
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.startsWith("N:", ignoreCase = true)) {
                return l.substring(2).split(';').filter { it.isNotBlank() }
                    .asReversed().joinToString(" ").trim().ifBlank { null }
            }
        }
        return null
    }

    /**
     * Whether the payload is a Web Tools code rather than something this phone can open itself.
     *
     * The companion page at gi-os.github.io/WebTools writes a shelf tool as JSON —
     * `{"wt":1,"n":"Tickets","u":"https://…","o":["ticketmaster.com"],"f":"Tickets"}` — and a code
     * too big for one image as `{"wt":1,"k":"part",…}`. Neither is a link, a network or a card, so
     * until now both arrived as TEXT with a COPY on them and nothing else: a code whose whole
     * purpose is to be installed, and no way to install it.
     *
     * **This recognises the envelope and does not parse the payload, deliberately.** Whether the
     * JSON is well formed, which origins the wall ends up with and whether a login unpacks are Web
     * Tools' questions, and it already owns the parser and the words for every way they fail. Roll
     * only has to know enough to offer the right verb; a payload that turns out to be broken gets
     * Web Tools' own reason rather than a second opinion formed here. Staying a string scan is
     * also what keeps this file free of `org.json`, whose Android stub returns nothing under a JVM
     * test and would have made the one untestable thing in it the one thing worth testing.
     */
    fun isWebTool(raw: String): Boolean {
        val t = raw.trim()
        if (!t.startsWith("{")) return false
        return jsonValue(t, "wt")?.toIntOrNull() != null
    }

    /** The name of the tool, the host it opens, or which part of a split code this one is. */
    private fun toolTitle(raw: String): String {
        if (jsonValue(raw, "k") == "part") {
            val i = jsonValue(raw, "i")?.toIntOrNull()
            val n = jsonValue(raw, "n")?.toIntOrNull()
            return if (i != null && n != null) "Part $i of $n" else "Part of a code"
        }
        val site = jsonValue(raw, "u")?.let { host(it) }
        val name = jsonValue(raw, "n").orEmpty().trim()
        // `n` is a name in one shape of this payload and a count of parts in the other. A bare
        // number is therefore not trusted as a title while there is a host to fall back on.
        val named = name.isNotEmpty() && !(site != null && name.all { it.isDigit() })
        return if (named) name else site ?: "Web tool"
    }

    /**
     * The value of one key, as text, or null when the key is not there.
     *
     * A scanner rather than a parser, and that is the right size for the job: these payloads are
     * flat objects a generator wrote, nothing here decides anything a mistake would be expensive
     * about, and the alternative is a JSON dependency in the one file that has none. A key whose
     * name appears somewhere it is not a key costs a wrong word in a title.
     */
    private fun jsonValue(raw: String, key: String): String? {
        val needle = "\"" + key + "\""
        var from = 0
        while (true) {
            val at = raw.indexOf(needle, from)
            if (at < 0) return null
            var i = at + needle.length
            while (i < raw.length && raw[i].isWhitespace()) i++
            // A match that is not followed by a colon was a value, not a key. Keep looking.
            if (i >= raw.length || raw[i] != ':') { from = at + 1; continue }
            i++
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return null
            if (raw[i] == '"') return readString(raw, i)
            var end = i
            while (end < raw.length && raw[end] != ',' && raw[end] != '}' && !raw[end].isWhitespace()) end++
            return raw.substring(i, end).ifEmpty { null }
        }
    }

    /** The JSON string starting at [quote], unquoted, with its escapes resolved. */
    private fun readString(raw: String, quote: Int): String {
        val out = StringBuilder()
        var i = quote + 1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') return out.toString()
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = raw[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    out.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                    i += 4
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }

    /**
     * Whether a string with no scheme is a web address anyway.
     *
     * Strict on purpose, and this is the rule `Patterns.WEB_URL` gets wrong: the last label has to
     * be a real top-level domain shape — two or more letters, no digits — so `example.com` and
     * `a.co.uk/x` complete to https, while `1.2`, `v2.0` and `roll.1` stay text.
     */
    fun isBareDomain(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 253 + 2048) return false
        if (t.any { it.isWhitespace() }) return false
        val authority = t.substringBefore('/').substringBefore('?').substringBefore('#')
        val labels = authority.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { label -> label.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' } }) {
            return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' }
    }
}

/**
 * Which decoded frames are news and which are the same poster still being pointed at.
 *
 * The analyser reads the code perhaps twenty times a second for as long as it is in shot, so
 * something has to decide that nineteen of those are not a scan. The window **slides**: every
 * suppressed read pushes the deadline out, so a code that stays in frame never fires twice no
 * matter how long you hold it, and the same code fires again only after it has been out of shot
 * for [repeatMs]. A fixed window would have re-fired every two seconds while you were reading the
 * result, which is the behaviour that makes scanners feel like they are arguing with you.
 *
 * A different code is always news, immediately — pointing at the next one on the page is a
 * deliberate act and waiting for a timer there would read as the camera being broken.
 */
class ScanGate(private val repeatMs: Long = 2_000L) {

    private var last: String? = null
    private var lastAt = 0L

    fun accept(text: String, now: Long): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t == last && now - lastAt < repeatMs) {
            lastAt = now
            return false
        }
        last = t
        lastAt = now
        return true
    }

    /** Forget everything, so the very next read counts. Used when QR mode is entered. */
    fun reset() {
        last = null
        lastAt = 0L
    }
}

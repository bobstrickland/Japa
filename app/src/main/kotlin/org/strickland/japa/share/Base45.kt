package org.strickland.japa.share

/**
 * Base45 encoding, as specified in RFC 9285.
 *
 * Its alphabet is exactly QR's alphanumeric character set, so a Base45 payload is encoded at
 * 5.5 bits per character instead of the 8 a byte-mode payload costs. That is roughly 29% more
 * prayer text in one code than Base64 would allow, which is the difference between a weekly set
 * fitting on screen and not.
 */
object Base45 {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private val REVERSE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    /** Two bytes become three characters; a trailing odd byte becomes two. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size / 2 * 3 + 2)
        var i = 0
        while (i + 1 < bytes.size) {
            val value = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            out.append(ALPHABET[value % 45])
            out.append(ALPHABET[(value / 45) % 45])
            out.append(ALPHABET[value / 45 / 45])
            i += 2
        }
        if (i < bytes.size) {
            val value = bytes[i].toInt() and 0xFF
            out.append(ALPHABET[value % 45])
            out.append(ALPHABET[value / 45])
        }
        return out.toString()
    }

    /** Returns null for anything that is not a well-formed Base45 string. */
    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val out = ArrayList<Byte>(text.length / 3 * 2 + 1)
        var i = 0
        while (i < text.length) {
            val remaining = text.length - i
            val chunk = if (remaining >= 3) 3 else 2
            var value = 0
            for (j in chunk - 1 downTo 0) {
                val digit = text.getOrNull(i + j)?.let { c ->
                    if (c.code < 128) REVERSE[c.code] else -1
                } ?: -1
                if (digit < 0) return null
                value = value * 45 + digit
            }
            if (chunk == 3) {
                if (value > 0xFFFF) return null
                out.add((value / 256).toByte())
                out.add((value % 256).toByte())
            } else {
                if (value > 0xFF) return null
                out.add(value.toByte())
            }
            i += chunk
        }
        return out.toByteArray()
    }
}

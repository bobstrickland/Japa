package org.strickland.japa.share

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base45Test {

    /** Test vectors from RFC 9285 section 4.4. */
    @Test
    fun `matches the RFC vectors`() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertEquals("QED8WEX0", Base45.encode("ietf!".toByteArray()))
    }

    @Test
    fun `decodes the RFC vectors`() {
        assertEquals("AB", Base45.decode("BB8")!!.decodeToString())
        assertEquals("Hello!!", Base45.decode("%69 VD92EX0")!!.decodeToString())
        assertEquals("base-45", Base45.decode("UJCLQE7W581")!!.decodeToString())
        assertEquals("ietf!", Base45.decode("QED8WEX0")!!.decodeToString())
    }

    @Test
    fun `round trips arbitrary bytes of every length`() {
        val random = Random(20260902)
        for (length in 0..300) {
            val bytes = random.nextBytes(length)
            val decoded = Base45.decode(Base45.encode(bytes))
            assertArrayEquals("length $length", bytes, decoded)
        }
    }

    @Test
    fun `round trips every single byte value`() {
        for (value in 0..255) {
            val bytes = byteArrayOf(value.toByte())
            assertArrayEquals(bytes, Base45.decode(Base45.encode(bytes)))
        }
    }

    @Test
    fun `uses only characters QR can encode in alphanumeric mode`() {
        val allowed = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:".toSet()
        val encoded = Base45.encode(Random(7).nextBytes(2000))
        assertEquals(emptySet<Char>(), encoded.toSet() - allowed)
    }

    @Test
    fun `rejects malformed input`() {
        assertNull("length 1 mod 3 is impossible", Base45.decode("A"))
        assertNull("character outside the alphabet", Base45.decode("ab8"))
        assertNull("chunk value overflows two bytes", Base45.decode(":::"))
    }
}

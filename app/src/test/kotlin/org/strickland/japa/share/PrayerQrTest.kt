package org.strickland.japa.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerQrTest {

    private fun entry(name: String, text: String) = PrayerBundle.Entry(name, text, null)

    @Test
    fun `round trips a named set in order`() {
        val entries = listOf(
            entry("Shanti Path", "Om shanti shanti shantih"),
            entry("Gayatri Mantra", "Om bhur bhuvah svaha")
        )
        val payload = PrayerQr.encode("Sunday Assembly", entries)
        assertNotNull(payload)

        val manifest = PrayerQr.decode(payload!!.text)
        assertNotNull(manifest)
        assertEquals("Sunday Assembly", manifest!!.setName)
        assertEquals(listOf("Shanti Path", "Gayatri Mantra"), manifest.entries.map { it.name })
        assertEquals("Om bhur bhuvah svaha", manifest.entries[1].text)
    }

    @Test
    fun `survives devanagari`() {
        val text = "ॐ भूर्भुवः स्वः\nतत्सवितुर्वरेण्यं\nभर्गो देवस्य धीमहि\nधियो यो नः प्रचोदयात्॥"
        val payload = PrayerQr.encode(null, listOf(entry("गायत्री मन्त्र", text)))!!
        val manifest = PrayerQr.decode(payload.text)!!
        assertEquals("गायत्री मन्त्र", manifest.entries[0].name)
        assertEquals(text, manifest.entries[0].text)
        assertNull(manifest.setName)
    }

    @Test
    fun `drops background images, which cannot travel by QR`() {
        val withImage = PrayerBundle.Entry("Test", "text", "img/abc.jpg")
        val manifest = PrayerQr.decode(PrayerQr.encode("S", listOf(withImage))!!.text)!!
        assertNull(manifest.entries[0].imagePath)
    }

    @Test
    fun `refuses a set too large for one code`() {
        // Random text so deflate cannot rescue it — repeated prose compresses away to nothing.
        val random = kotlin.random.Random(11)
        val alphabet = ('a'..'z') + ' '
        val huge = (1..20).map { i ->
            entry("Prayer $i", (1..1200).map { alphabet.random(random) }.joinToString(""))
        }
        assertNull(PrayerQr.encode("Too Big", huge))
    }

    @Test
    fun `compression keeps a long devanagari prayer well inside the budget`() {
        // Stands in for the Hanuman Chalisa: long, and highly repetitive as Devanagari is.
        val chalisa = "श्रीगुरु चरन सरोज रज निज मनु मुकुरु सुधारि। ".repeat(70)
        val used = PrayerQr.charCount("Sunday Assembly", listOf(entry("Hanuman Chalisa", chalisa)))
        assertTrue("used $used of ${PrayerQr.capacity()}", used < PrayerQr.capacity())
    }

    @Test
    fun `ignores codes that are not ours`() {
        assertNull(PrayerQr.decode("https://example.com"))
        assertNull(PrayerQr.decode("JAPA1:not-valid-base45!!"))
        assertNull(PrayerQr.decode(""))
    }

    @Test
    fun `empty set produces no code`() {
        assertNull(PrayerQr.encode("Empty", emptyList()))
    }
}

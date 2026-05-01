package io.github.thatsfguy.reticulum.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagePackTest {

    @Test fun nilTrueFalse() {
        assertContentEquals(byteArrayOf(0xC0.toByte()), MessagePack.encode(null))
        assertContentEquals(byteArrayOf(0xC3.toByte()), MessagePack.encode(true))
        assertContentEquals(byteArrayOf(0xC2.toByte()), MessagePack.encode(false))
        assertEquals(null, MessagePack.decode(byteArrayOf(0xC0.toByte())))
        assertEquals(true, MessagePack.decode(byteArrayOf(0xC3.toByte())))
        assertEquals(false, MessagePack.decode(byteArrayOf(0xC2.toByte())))
    }

    @Test fun positiveFixint() {
        for (i in 0..0x7F) {
            val enc = MessagePack.encode(i)
            assertContentEquals(byteArrayOf(i.toByte()), enc)
            assertEquals(i.toLong(), MessagePack.decode(enc))
        }
    }

    @Test fun negativeFixint() {
        for (i in -32..-1) {
            val enc = MessagePack.encode(i)
            assertEquals(i.toLong(), MessagePack.decode(enc))
        }
    }

    @Test fun float64RoundTrip() {
        val v = 1735742400.123456
        val enc = MessagePack.encode(v)
        assertEquals(0xCB.toByte(), enc[0])
        assertEquals(v, MessagePack.decode(enc))
    }

    @Test fun fixstr() {
        val enc = MessagePack.encode("Alice")
        // 0xA5 + "Alice"
        assertEquals(0xA5.toByte(), enc[0])
        assertEquals("Alice", MessagePack.decode(enc))
    }

    @Test fun bin8() {
        val data = ByteArray(10) { it.toByte() }
        val enc = MessagePack.encode(data)
        assertEquals(0xC4.toByte(), enc[0])
        assertEquals(10, enc[1].toInt())
        val decoded = MessagePack.decode(enc)
        assertTrue(decoded is ByteArray)
        assertContentEquals(data, decoded)
    }

    @Test fun lxmfShape() {
        // [timestamp:double, title:bin, content:bin, fields:map]
        val ts = 1735742400.0
        val title = "T".encodeToByteArray()
        val content = "hello".encodeToByteArray()
        val fields = emptyMap<Any?, Any?>()
        val enc = MessagePack.encode(listOf(ts, title, content, fields))
        val decoded = MessagePack.decode(enc) as List<*>
        assertEquals(4, decoded.size)
        assertEquals(ts, decoded[0])
        assertContentEquals(title, decoded[1] as ByteArray)
        assertContentEquals(content, decoded[2] as ByteArray)
        assertEquals(0, (decoded[3] as Map<*, *>).size)
    }

    @Test fun emptyMapAndArray() {
        // Empty fixmap = 0x80, empty fixarray = 0x90
        assertContentEquals(byteArrayOf(0x80.toByte()), MessagePack.encode(emptyMap<Any, Any>()))
        assertContentEquals(byteArrayOf(0x90.toByte()), MessagePack.encode(emptyList<Any>()))
    }
}

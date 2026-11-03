package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterfaceConfigTest {

    private val snippet = """
        [[Example Node]]
          type = TCPClientInterface
          enabled = yes
          target_host = node.example.net
          target_port = 4242
          network_name = example-net
          passphrase = correct horse battery
          ifac_size = 64
    """.trimIndent()

    @Test fun `operator snippet with IFAC parses`() {
        val c = parseTcpInterfaceConfig(snippet)!!
        assertEquals("Example Node", c.name)
        assertEquals("node.example.net", c.host)
        assertEquals(4242, c.port)
        assertEquals("example-net", c.networkName)
        assertEquals("correct horse battery", c.passphrase)
        assertEquals(8, c.ifacSizeBytes)
        assertTrue(c.hasIfac)
    }

    @Test fun `upstream alias keys and the default size are honoured`() {
        val c = parseTcpInterfaceConfig(
            "[[n]]\ntype = TCPClientInterface\ntarget_host = 192.0.2.7\ntarget_port = 7822\n" +
                "networkname = example-net\npass_phrase = secret\n",
        )!!
        assertEquals("example-net", c.networkName)
        assertEquals("secret", c.passphrase)
        assertNull(c.ifacSizeBytes)
        assertEquals(16, c.effectiveIfacSizeBytes)
    }

    @Test fun `plain TCP block has no IFAC`() {
        val c = parseTcpInterfaceConfig("type = TCPClientInterface\ntarget_host = h.example\ntarget_port = 1")!!
        assertFalse(c.hasIfac)
        assertNull(c.name)
    }

    @Test fun `full config with an interfaces section still parses one interface`() {
        val c = parseTcpInterfaceConfig("[interfaces]\n" + snippet)!!
        assertEquals("node.example.net", c.host)
    }

    @Test fun `untrusted input is refused`() {
        assertNull(parseTcpInterfaceConfig(snippet.replace("TCPClientInterface", "RNodeInterface")))
        assertNull(parseTcpInterfaceConfig(snippet.replace("4242", "70000")))
        assertNull(parseTcpInterfaceConfig(snippet.replace("node.example.net", "http://evil/x")))
        assertNull(parseTcpInterfaceConfig(snippet + "\n[[Second]]\ntype = TCPClientInterface\n"))
        assertNull(parseTcpInterfaceConfig("not a config"))
        assertNull(parseTcpInterfaceConfig(snippet + "\n# " + "x".repeat(5000)))
    }

    @Test fun `toString never shows the passphrase`() {
        assertFalse("correct horse" in parseTcpInterfaceConfig(snippet)!!.toString())
    }
}

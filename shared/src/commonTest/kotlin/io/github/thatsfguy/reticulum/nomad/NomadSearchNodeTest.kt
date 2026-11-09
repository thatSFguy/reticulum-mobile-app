package io.github.thatsfguy.reticulum.nomad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NomadSearchNodeTest {
    private val hash = "0123456789abcdef0123456789abcdef"

    @Test
    fun acceptsBareHash() {
        assertEquals(hash, parseNomadSearchNodeInput(hash))
    }

    @Test
    fun normalizesCaseAndSurroundingSpace() {
        assertEquals(hash, parseNomadSearchNodeInput("  ${hash.uppercase()}\n"))
    }

    @Test
    fun takesHashFromPastedPageLink() {
        assertEquals(hash, parseNomadSearchNodeInput("$hash:/page/index.mu"))
        assertEquals(hash, parseNomadSearchNodeInput("nnn@$hash"))
    }

    @Test
    fun rejectsPartialHash() {
        assertNull(parseNomadSearchNodeInput(hash.dropLast(1)))
    }

    @Test
    fun rejectsTargetsThatNameNoNode() {
        assertNull(parseNomadSearchNodeInput("/page/index.mu"))
        assertNull(parseNomadSearchNodeInput("lxmf@$hash"))
        assertNull(parseNomadSearchNodeInput("not a hash"))
        assertNull(parseNomadSearchNodeInput(""))
    }

    @Test
    fun neverChangedUsesDefault() {
        assertEquals(NOMAD_SEARCH_DEFAULT_NODE, resolveNomadSearchNode(null))
    }

    @Test
    fun blankTurnsSearchOff() {
        assertNull(resolveNomadSearchNode(""))
        assertNull(resolveNomadSearchNode("   "))
    }

    @Test
    fun customNodeWins() {
        assertEquals(hash, resolveNomadSearchNode(hash))
    }

    @Test
    fun defaultNodeIsAValidHash() {
        assertEquals(NOMAD_SEARCH_DEFAULT_NODE, parseNomadSearchNodeInput(NOMAD_SEARCH_DEFAULT_NODE))
    }

    @Test
    fun postDataMatchesFormFieldConvention() {
        assertEquals(mapOf("field_query" to "solar power"), nomadSearchPostData("  solar power "))
    }
}

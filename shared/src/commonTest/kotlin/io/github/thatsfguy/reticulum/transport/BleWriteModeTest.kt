package io.github.thatsfguy.reticulum.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleWriteModeTest {
    @Test
    fun esp32RnodeOfferingOnlyAcknowledgedWritesGetsThem() {
        assertEquals(
            BleWriteMode.WithResponse,
            bleWriteModeFor(supportsWithoutResponse = false, supportsWithResponse = true),
        )
    }

    @Test
    fun withoutResponseIsPreferredWhenBothAreOffered() {
        assertEquals(
            BleWriteMode.WithoutResponse,
            bleWriteModeFor(supportsWithoutResponse = true, supportsWithResponse = true),
        )
    }

    @Test
    fun withoutResponseOnlyIsUsed() {
        assertEquals(
            BleWriteMode.WithoutResponse,
            bleWriteModeFor(supportsWithoutResponse = true, supportsWithResponse = false),
        )
    }

    @Test
    fun aCharacteristicThatTakesNoWritesHasNoMode() {
        assertNull(bleWriteModeFor(supportsWithoutResponse = false, supportsWithResponse = false))
    }
}

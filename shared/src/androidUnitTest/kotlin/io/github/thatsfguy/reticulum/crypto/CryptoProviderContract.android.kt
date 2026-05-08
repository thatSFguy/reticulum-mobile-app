package io.github.thatsfguy.reticulum.crypto

import io.github.thatsfguy.reticulum.platform.AndroidCryptoProvider

actual fun testCryptoProvider(): CryptoProvider = AndroidCryptoProvider()

package io.github.thatsfguy.reticulum.crypto

import io.github.thatsfguy.reticulum.platform.IosCryptoProvider

actual fun testCryptoProvider(): CryptoProvider = IosCryptoProvider()

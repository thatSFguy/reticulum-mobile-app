package io.github.thatsfguy.reticulum.crypto

/**
 * Test-only factory for a platform CryptoProvider. Each platform's test
 * source set provides an `actual` returning its real provider:
 *   - androidUnitTest → AndroidCryptoProvider (Bouncy Castle + JCA)
 *   - iosTest         → IosCryptoProvider (CryptoKit + CommonCrypto)
 *
 * The shared contract tests in [CryptoProviderContractTest] then run
 * the same byte-level assertions against both implementations,
 * catching divergences like the v1.0.2 iOS aesCbcEncrypt IV-prefix
 * bug that broke outbound LXMF.
 */
expect fun testCryptoProvider(): CryptoProvider

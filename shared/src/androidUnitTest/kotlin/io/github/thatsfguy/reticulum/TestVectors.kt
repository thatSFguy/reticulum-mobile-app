package io.github.thatsfguy.reticulum

import io.github.thatsfguy.reticulum.platform.AndroidCryptoProvider
import io.github.thatsfguy.reticulum.transport.hexToBytes

/**
 * Constants extracted from reference/test-vectors.json. These are the same
 * bytes the JS reference implementation passes its tests against; the Kotlin
 * port must produce the same outputs.
 */
object TestVectors {

    val crypto = AndroidCryptoProvider()

    object Alice {
        val encPriv     = "859771582966c5ce9f4433dffca70e97cf6134c2d1ea7b8eccac85da24299009".hexToBytes()
        val sigPriv     = "30d13e0cb6171d246e09ecabb9018b75c1b6faef4a3e7484675990f47d4d51a4".hexToBytes()
        val ratchetPriv = "498f86ccc11a65a588e381279cd977d331fc79ba54fc3bb8d361d577221c5da3".hexToBytes()
        val encPub      = "124d33d0e72f42b678d9f62c612f6c2d8d9a74a396e83cf72eb7c00b049bf706".hexToBytes()
        val sigPub      = "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec".hexToBytes()
        val ratchetPub  = "6e762f063fa6360ff0673f95f15cc6de8d66ceebb36975ddc5b8f447b95e7120".hexToBytes()
        val publicKey   = ("124d33d0e72f42b678d9f62c612f6c2d8d9a74a396e83cf72eb7c00b049bf706" +
                           "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec").hexToBytes()
        val identityHash = "a1b3ea629ced428209e1dbf785ac1402".hexToBytes()
        val destHash     = "28b067284f739eead728dc74a7f0f064".hexToBytes()
    }

    object Bob {
        val encPriv     = "1b853674a9e6c99de171e53b9f6328f8b76f265ff281117c9f3112880c23f87a".hexToBytes()
        val sigPriv     = "a15ed06784f5a1779ab97a83f32fe6f32b38e8eaab6932e01407f16ef8e7ce7d".hexToBytes()
        val ratchetPriv = "eb2431102b4dd64cd06bbdc8afa6b263bcb4459869f0aa4ed4722ce552ac18a1".hexToBytes()
        val encPub      = "5f206374fc1467f269725338cc61dd5c41e8a9d4b305a1d073b7f4149aacd342".hexToBytes()
        val sigPub      = "1522f69418cfe8f1aff27a76b0794425febd553b5e3bb126d282fdd4fccce58d".hexToBytes()
        val ratchetPub  = "4039e9b5ff616f85b4928ecd4895c1696c14b552b7db489d214ab2498411e15c".hexToBytes()
        val publicKey   = ("5f206374fc1467f269725338cc61dd5c41e8a9d4b305a1d073b7f4149aacd342" +
                           "1522f69418cfe8f1aff27a76b0794425febd553b5e3bb126d282fdd4fccce58d").hexToBytes()
        val identityHash = "78eb70f61b79245e6cfebd56ece88372".hexToBytes()
        val destHash     = "d50b0d0940d164c1f11ea51703ecb631".hexToBytes()
    }

    object Announce {
        const val displayName = "AliceTest"
        const val hasRatchet = true
        val packet = ("210028b067284f739eead728dc74a7f0f06400" +
                      "124d33d0e72f42b678d9f62c612f6c2d8d9a74a396e83cf72eb7c00b049bf706" +
                      "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec" +
                      "6ec60bc318e2c0f0d908" +
                      "9b31d3867ec9b9ded179" +
                      "6e762f063fa6360ff0673f95f15cc6de8d66ceebb36975ddc5b8f447b95e7120" +
                      "76dfd92230e30fcd8f72f4b86e2cbe893d4cd210b981e32e7365438dcc567e29" +
                      "a27ec506a6ed83b89ffde89b664d49fb7e6de2417af5822948e69a4b4217600b" +
                      "92c4" + "09" + "416c6963655465737400").hexToBytes()
    }

    object LxmfSend {
        const val content = "hello from tests/roundtrip.mjs"
        val packet = ("0000d50b0d0940d164c1f11ea51703ecb63100" +
                      "e427ed8bf89195ca062f18ca57089398d388811d68c4f5ff77a50cd480dd445a" +
                      "398381ccc6c2d62e2ba22f53c1230ef8" +
                      "691f26ec596a6eab28580225c6da055eafa2dda1c520563722c5c38e99b67f23" +
                      "6f71787a423537a562132091bb92eb092ef5159e7b774dc021724a023e88935f" +
                      "7dbea8c1c2217b340633931d27098527237c715b67f063c742e320882099a785" +
                      "4e4d809523bfd5b5b869f2568307e35e7a3007aece75177c487458218e4edc5a" +
                      "296ba19caa684dbfef32f53d521a5135cfb92bad9ba62a913b3799baddb1fb1b").hexToBytes()
    }

    object Link {
        val linkRequestPacket = ("020028b067284f739eead728dc74a7f0f064004a4b4c4d4e4f50515253545556575859" +
                                 "606162636465666768697071727374758081828384858687888990919293949596979899" +
                                 "aabbccddeeff0001020304052001f4").hexToBytes()
        val linkId       = "ef85c91f9202cefef5b899ebba114a1b".hexToBytes()
        val lrProofPacket = ("0f00ef85c91f9202cefef5b899ebba114a1bff" +
                             "9fb5cdfb8b87fdb5b085ac224a87b9694c7e27a4566110b073cbf492c747b213" +
                             "c055d68833fdde8545c7a84acf9497b5d4ace3cff6b15a1a4bf3ade2ee30910c" +
                             "25a10290491b0c04004b0eaa86a4aca64f4b0e67c8ad368f48e2811a714f9033" +
                             "2001f4").hexToBytes()
        val signedData   = ("ef85c91f9202cefef5b899ebba114a1b" +
                            "25a10290491b0c04004b0eaa86a4aca64f4b0e67c8ad368f48e2811a714f9033" +
                            "0677ce05ff0dcd9dcea720bdad2f88202b2c8dc2a4649699f1e41524624ea1ec" +
                            "2001f4").hexToBytes()
    }

    object LinkHandshake {
        val linkIdResponder = "48175aa00036e2ba6f818e09eadf2d87".hexToBytes()
        val derivedKey = ("7ba9191669703527462b1b029a147d5c307f9c3b53d75c41a5c55b9cecb16495" +
                          "71f4eb69094f92af959572394c250c3f7f5bb88ffe3aa23fc4f73176f9126f1a").hexToBytes()
        val testCiphertext = ("b341e1450948ad696042ad249e61dd5b301793c6a2d2fa317f73ed62a9fbb1ca" +
                              "c4d1c249dae52c5f640557a4e379f1f028d6f68649db4e2af1679cb0c81be29e" +
                              "f554cad5ff0ed6d53a7e22647e4711fd").hexToBytes()
        const val testPlaintext = "hi over link from alice"
    }
}

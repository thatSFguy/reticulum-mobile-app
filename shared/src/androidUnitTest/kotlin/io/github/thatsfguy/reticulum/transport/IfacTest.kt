package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.TestVectors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * IFAC pinned to upstream RNS 1.5.7 bytes. Vectors come from
 * Transport.handle_outgoing_ifac (cross-checked against
 * handle_outgoing_ifac_legacy and round-tripped through handle_ifac) with
 * placeholder network names / passphrases — never real credentials.
 *
 * Self-round-trip alone would pass with a wrong mask, salt or tag slice on
 * both ends (CLAUDE.md RULE #1), so every case compares against upstream.
 */
class IfacTest {

    private val crypto = TestVectors.crypto

    private data class V(val key: Int, val size: Int, val packet: Int, val sealedHex: String)

    /** (network_name, passphrase, derived 64-byte key) */
    private val keys = listOf(
        Triple("testnet", "hunter2", "a1cd332c7b57176c2ccd49136c10373a7b18ff74ea2f48c05583f676d6a702e61b9f8f980f025582b3a7243b24055caaca3c89ab17644fcbbbc016e9edd77eb2"),
        Triple("example-net", "", "634230814597473975620d62cb343c52acc320ab2404ae828daa450c47c83049e0b26675010cf4998984f4fbf0c88e05f1252e9377d83f5a258d7fa8aacae242"),
        Triple("", "only-a-passphrase", "96f21c0ac0c35f5775196fa9e6e6b85ec6c6f6e8c6106913c348a78ebe22bfbb5951cdffcbdf50ef989d1f6e697311d17b64575e0f8e3965076221c07b0e9017"),
    )
    private val packets = listOf(
        "00001111111111111111111111111111111100deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        "5103222222222222222222222222222222223333333333333333333333333333333300abababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababababab",
        "0c0144444444444444444444444444444444fe01",
    ).map { it.hexToBytes() }
    private val vectors = listOf(
        V(0, 8, 0, "a72d57178ec760255c0a823dfc06a957129fb780913538b81823beb172d6e48c32ff06559e4909d157ff497e0b16caba08fc402024276533a12dddf51661a354b17237"),
        V(0, 8, 1, "c9dde8ec3c3df94ae80a90f156ce51985075a0448c7f67db3245d6cc8b14f242539382e711fbb985fdb93ee43550d0215cd900a911441e486948c79a90f72e851d93b8a9c6c92c2c2d99c675e4dd047e34b9abb8ade8686cd6425df6867d5181e461e219851b3a805852ac25598b226f1a3ae8fb94421c1fe07299d87ce4d15a5088771d2b4e5d86e231639dda118303b20218067ee589803da3342ee710e0d63313df2c32a60f56df006cd5e819d191e71644e48a5fc764c2781bcb8c1680d7111e0f9c60fb452f77db4b05103bfb41e39881fac58c6f187afba7ac38c6db0e96a9715f80f2c0000940eefa75d5daa5a80af9"),
        V(0, 8, 2, "ae194f697e0d810b1c04bb4e97c1770af58e9c475e2ad561c8548b21"),
        V(0, 16, 0, "9bd7c6000794c774b29657178ec760255c0a16b9e7bc01919c72e06cbfacbdc180babe1a84577231e7a1e7679d5f4f21c459246c410e8322bc2ba87e155704840a788bbaebf6fd34e9a31a"),
        V(0, 16, 1, "87ab1b13bf329e23c22fe8ec3c3df94ae80a2d463e4cf50b8cb1cde3a17f4985823504f924e64e1266c660bf2083888a81e55e4fc960554f7ef3dfcec2fb4afd8f4c70b0ee04ae31a41ef7f76f936bb6621564cc1bcfbeb24593ed7dfb139f9ffa66050ad1e876c0a4b63054204229556086be90868d3d6dcec76c77611ff284361ba9daea7b976094b7473b6c848d16b0605460412ff59fe1f83acf39616fb9cebc9e8903f8c07c2b396bfa4c5c165d41f0aeda4ee50c3832934c2aa52c0918863529beba825a34557a26371cee2f78a71343cc0649fe4788a6bc2f6fee63eca88cc14b6eb491a4dcbfac4ca90fe56b2834c404d1746b1b108385"),
        V(0, 16, 2, "fc198b90bf00ad74645d4f697e0d810b1c0478e54ad87723ddda79e451908e1aa97a3de8"),
        V(0, 64, 0, "ec0a22936d5e1979e7aac9e86ad56114274d3722351f7f954b6025589b9e3dab9cbd54de92d82379050cbd26807a8f31b1c7c6000794c774b29657178ec760255c0a76c1b5fee0f6dc41f8120cee302d9b4e7ee80d5ce1f63061682db106827a3927a68ff1ced965ca612a42ed92ef5d6de091a5ef0d7f96a618c6"),
        V(0, 64, 1, "c409859efc0233a4938b738758b42c2cd3d9c2d7dac692ec92f72123e9c6ce50c17a90491d11c8248b00b0844aa12095de301b13bf329e23c22fe8ec3c3df94ae80aed6abc362bcd54989b1244f31d767b7c244c22906595a90eb7a042c4d0d170eb2cfeda4151af044500c8352b4e6620782131db00b9eb87f164c13b852b7c7e24ba85b37ba1efea194d85d3d2cc1951dcca052a6835c4c3527bf162c76df6282211e94634d94d39cebe241c58aae979e2f960c3e842a8e2eb936dda9ee8ba5d2f77b61922bd7658c14074a01d225f7f7e79f4c0dd42ed13654ad721dd782372c8f6deced997357c7ef7ebc513995fb2d2b67a7f5c536c3c5a2dd1a182b9d136d9bccd99b41fbd88ff8c713b5b5dc30871b0ee496a3451d030c35ad4b535caa2e94715500fff4529daac"),
        V(0, 64, 2, "ad5b3343df5dd484efa56b1ebeb127e5c61c8a35c51bd31e2f2152447807ef1f7871b47eddd5b994ea9071e10d02ab5c14498b90bf00ad74645d4f697e0d810b1c04571aa33a62f1949f629c2ec398ba7e2b42c0"),
        V(1, 8, 0, "894d174ea94c42be7105b6d9c10ddc9a40adbd136e80fee424b474a1dff55293cd8e33b7af3af1f315cb0f46c1178efce588db0062c78af699c927d5760acf245c2c2e"),
        V(1, 8, 1, "ea20556d80e134f36e0c55e13689adf940b1a41e0ed9f087a934f177a52d3514f1453bc7a60891656223c607dff3816545e2f4c0375dc42566cf58d472a16e798c2578566389636cd74a3df804f2c03ecf18aa4e8226350c14a8655fb4717de89bc0da3d49e5685201813e83c4dd5ac0e9bc49da7211a06762a1787f83a9fb4edb0e6b64142f4a9ffb7a3ca5faf62eccf9f204314290b88db93c011a2d13dfec7059b0de0fba321a70054e2cea5531d82d9b809685943f2e336409596651e547e3014e8b458f093e5b7e29b4ed25f64a51cd3f5cef5c2dac3bf0d4552bba7bf94f6f2ce0e281266ae6838f51be81ae4d9e2813"),
        V(1, 8, 2, "85317e3e1bc1aeec3406e3894eb418c9f1c26fdcc82cf65be416915c"),
        V(1, 16, 0, "c04b85ab9fe39fe42d27174ea94c42be7105c6ebb2c180203200e5bd2097315e409053d7e4237837e8f94d9755c16761ee8192a4c96f906a9ad8a708520c2ef2a3429f7cf5e87a32e29abc"),
        V(1, 16, 1, "f458e6362d4a58e0c402556d80e134f36e0c8452b9e8731588daa3f04652011a2cdb0e65a27c18165df343cc12e10bdad08698468d59b4211e8c963baff76a43c992f7111ec482794c77a309b43bc3bd64d4463d72becb5d0cb340d6682876bb3405b21af51155b4e7a97d1049ceb44e1f5690ae48836c9e04e0eb4a8a2605720adc40cd1814997e2c904d3636eaf08bc29d4e3fcb9ac2fff57c414bca3891d0c6a06367ae5f9e6ff51350ac2a522e89bdd4eb6ba6ed3539e7504df4f23e6274eb85dff373582c404e6af1afef6ab8a04cce0082f8c13e9bc098a6d540cf0613ec1016ea9c0b3d650f8fcdc13924b8742abd1e23c721a4c993c8b4"),
        V(1, 16, 2, "9a2369cbc419a03f20e57e3e1bc1aeec34067cb86cd5ff8951daab8383d987cb63ab32cd"),
        V(1, 64, 0, "cd16014e4a322f7ffc486554c061f93c3067c0d9057f42e078a7a0bf9cf4edc8f92917688ba20b5fd2f921dd71912187901a85ab9fe39fe42d27174ea94c42be7105232db0cee11b96c56514804f9d722c75f9dbd6b7cdb7eb19aa40c89d5618489951b74190a697e72fdaa5e01892cb8082b7d795d279cbd50e2f"),
        V(1, 64, 1, "a1f9276d617e109a7012bcd61d7c12a08317e8439dd684e7e616b6541ea3853f07e0c37ae542914a0c6f5401b493dcc3ff8ae6362d4a58e0c402556d80e134f36e0c773859129086dcd7a330fbf9c33371b7efef7313a4a839a0d074af343941bf6178ff71d2a7e4b5a41cdda0fe523eb8c99e0532156848a551248cc59d331bbe749a1abbc94b1b43ca11cf2bcd0694ce4894a7405c749f958f1c4e4831c1716d871736814fb5ca5efe55b74454c72cb494001441babba78f32c79e30868c9d5068f8719d2bca1bd444979290071b8da2826a0f25fe8e3aeecdecbb0fcbe19028c61cb5ca63871736dc6ba26fe14bbaeb2a7caaf3ed671a6ee9c47cbf213b7e018b3d8d7d070d825c8c99f0ebd7ecf1c3f37a97578e7769998f2d52381cbeb86d05eb5cf819315460f888"),
        V(1, 64, 2, "95883807be9566e269c88d4fa86ef668acf9f73902476979b426a193006f37964146b2bb6968349d3265c904fdee22a5bdbe69cbc419a03f20e57e3e1bc1aeec3406e894d9d43562f32dfa74b41fb96921f75bbf"),
        V(2, 8, 0, "ae3882cf8a36e395d701a867eb5c299b9610841b58ecde27d1cb4ab3fffb9cf149e3f52add92e321bf93005d7caed4cedb54515d61f059e2e47132b2e60b5ab594ef11"),
        V(2, 8, 1, "88da0fc56f86be34bd084a57222fa4af504ea27870b8ab7b2876b8fb1a4241c5ac259e8676c3ca9fcf4c46cb9e323a4e1d14c95306cc0aeaae1cb523c8cc6506f1f162d85cfcc1451bb83df5731fd1014b710e53f4cd427523dcc8fd8abf609b9db169529da7b83e106ccf771cea0726940484e3214d399fb9bdc4f1db965e097181e7ad668654cfd1ea62122afb209956fe7bf0ef2268491710275548843c10d34eff9777365e7ea44ccb0b555460aaeb9367b9e00d3a2005a6024ab4513a2021fc73655ae0e7edce8e304c5c185e9860709041672d62403c8cb82da8031c53c21b9c7a8b75e518090e68f9e5600bb2da136e"),
        V(2, 8, 2, "cfc0ed2c76cbdb15c50973ff1a5ee13b5ee7a6b5b50d5397e380c296"),
        V(2, 16, 0, "9c45ab83e7c4a8f8948d82cf8a36e395d701a8090102d42e646b901fb4827d18ef582113d6e67274ed4eb68ddfc957e19f210a84ebf7d72fee5e698082c7c67488f91d0a07be0527ab855a"),
        V(2, 16, 1, "bdbbe7be7f771e71ceb10fc56f86be34bd0803f21c768bb553717df5d161016f316c26922fd76584838c9323e3c5651ccd284a318191c508a7257591d2cbe4a13994d564c556a0ed408ffef36c82ac61765e25015291907a1edc317bdd21ed3d7fd93fb43cd5191761046607c58f551138dd9cab54638d484facd1bb7fcff92582052a33958df96a84f0a3d7d6a2f8d1f3565bd3dccd089d26480bbc5dbb3c7655b7fb25b65c7eb287088b732fa466cf8a6c2d6fddef79a9a82728a0eaf746d03d3728ff40f50fd9a2d423df498ba72f1433f15abe3144377d1bab38445912bf36c47b07fa4d60a8f7808672d9e38bde901feee5a4acb61d38f2e1"),
        V(2, 16, 2, "8e84141f0812fd2e9d8ced2c76cbdb15c5093daa60b4a283222eec21c88a5456e02fdf3e"),
        V(2, 64, 0, "f20208f1524250fe51f68866615a0f5472e431303b2e5a092ece47d97e77f990dfadc813c518c3ab31261742938e5e0896c0ab83e7c4a8f8948d82cf8a36e395d7014be9ffa3870ba49f289dd53b914c8d86755f1dbe43b7d641bf51ca261808cf66eb08e98e180045527977491e3174bd9d494e97a15f76a4cb78"),
        V(2, 64, 1, "cf0be14eacb48d0eb64bd7051b1f4480a7e4b94fb4950634e4301b77f84a02d94a7648b7c12a5d0e147d0fe68753d7815d2ae7be7f771e71ceb10fc56f86be34bd08da2b93764fc2723a3d9022f5df0b607fba26867acfb0b6ec9d6bbed91ece2e8cd9d3414370c5c41fa61662e43a4a107f1009455d9faa13a52aeeea7988756cbc1dede8f0a9bbcbe86ec45a6445cd1f91d7f4356e062cc3fa25a270ad8d567c96ca351d7fc524c54a8b15c73678a98358d3e56815b124bff3026f8e9c575667e7837d06d616ee5087b381b68d3e2d0ac88c0725ce7c4bea52647edf0c3c8a12514ccd12f0a9e92d0f0bb2b127ff9420105acd0acd5c72c58609dad0b3b6979040a6861664363df84e443059030d89aaea3d6518ac4445df9005713e5ae513fa3385fd9dd8e44c396b5e"),
        V(2, 64, 2, "d40928b1b568b2bec3c4352841334c83a84d2836451f0bb7577a0c09a7a59a7a9d7622dc910809a7a2aec412cad6860c1e9c141f0812fd2e9d8ced2c76cbdb15c509bd9697e3aa74dbfdb01e5ea870805af964a7"),
    )
    /** 9000-byte frame: the mask outruns 255 HKDF blocks, so this pins
     *  upstream's counter wrap. (key, size, SHA-256 of sealed frame) */
    private val bigVectors = listOf(
        Triple(0, 8, "231660a6943af8fc0162c6ca353103a507f9a89d414ecb0ae8ecd3f662aea933"),
        Triple(0, 16, "0fffc732b7f21ebcf2bcd486cd93ef0c0d8498fbfa7d9deadbf242441283d2f0"),
        Triple(0, 64, "3be5a49bde394fae618619498f3f5c49c140b0d3343c5ae4eec27b0033c380f9"),
        Triple(1, 8, "35318e463e9541d49a8055f5fcbb1ce7a64282c1c45b2804ea93b665f6ad76d7"),
        Triple(1, 16, "a1c4829c280853679b36db7c5c1fbe358b1985c5dfad15070035262e78f7caa5"),
        Triple(1, 64, "1b48058262b3848539a78019ad81055e0063ff7f97efa4b1ac4663edeff3882a"),
        Triple(2, 8, "911ef8ee6240c66fb4e40fa3402e89b626df4449fd2e5e5c2eb62519538c81f4"),
        Triple(2, 16, "7196df8b360d8057ff81a0b27ce93ab9512fb059647af486d1ae10d001b6eeb8"),
        Triple(2, 64, "79566972caa96888fab3cff71fb81b36c8d62c5758674cf845b82bafdfa101c7"),
    )
    private val bigPacket = byteArrayOf(0x08, 0x02) + ByteArray(9000) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    @Test fun `key derivation matches upstream`() = runTest {
        for ((name, pass, hex) in keys) {
            assertEquals(hex, IfacKey.derive(crypto, name, pass).toHex(), "derive($name, $pass)")
        }
    }

    @Test fun `seal matches upstream and unseal recovers the packet`() = runTest {
        for (v in vectors) {
            val key = IfacKey.fromKey(crypto, keys[v.key].third.hexToBytes(), v.size)
            val sealed = key.seal(packets[v.packet])
            assertEquals(v.sealedHex, sealed.toHex(), "seal key=${v.key} size=${v.size} pkt=${v.packet}")
            assertContentEquals(packets[v.packet], key.unseal(v.sealedHex.hexToBytes()))
        }
    }

    @Test fun `long frame matches upstream across the HKDF counter wrap`() = runTest {
        for ((k, size, sha) in bigVectors) {
            val key = IfacKey.fromKey(crypto, keys[k].third.hexToBytes(), size)
            val sealed = key.seal(bigPacket)
            assertEquals(sha, crypto.sha256(sealed).toHex(), "big key=$k size=$size")
            assertContentEquals(bigPacket, key.unseal(sealed))
        }
    }

    @Test fun `wrong key, tampering, missing flag and short frames are rejected`() = runTest {
        val v = vectors.first { it.size == 8 && it.packet == 0 }
        val right = IfacKey.fromKey(crypto, keys[v.key].third.hexToBytes(), 8)
        val wrong = IfacKey.fromKey(crypto, keys[(v.key + 1) % keys.size].third.hexToBytes(), 8)
        val sealed = v.sealedHex.hexToBytes()

        assertNull(wrong.unseal(sealed), "another network's key must not open it")
        assertNull(right.unseal(sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }))
        assertNull(right.unseal(sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }), "tag tamper")
        assertNull(right.unseal(packets[0]), "plain packet without the IFAC flag")
        assertNull(right.unseal(sealed.copyOf(10)), "frame not longer than header + tag")
    }

    @Test fun `IfacTransport seals outbound and drops unauthenticated inbound`() = runTest {
        val key = IfacKey.fromKey(crypto, keys[0].third.hexToBytes(), 8)
        val inner = FakeTransport()
        val violations = mutableListOf<String>()
        val t = IfacTransport(inner, key) { violations += it }

        t.send(packets[0])
        assertContentEquals(key.seal(packets[0]), inner.sent.single())

        val got = mutableListOf<ByteArray>()
        val job = launch { t.incoming.collect { got += it.packet } }
        yield()
        inner.rx.emit(IncomingPacket(packets[1]))                 // no IFAC flag
        inner.rx.emit(IncomingPacket(key.seal(packets[2])))       // valid
        inner.rx.emit(IncomingPacket(IfacKey.fromKey(crypto, keys[1].third.hexToBytes(), 8).seal(packets[2])))
        yield()
        job.cancel()

        assertEquals(1, got.size)
        assertContentEquals(packets[2], got.single())
        assertEquals(listOf("missing IFAC flag", "invalid IFAC tag"), violations)
    }

    private class FakeTransport : Transport {
        val sent = mutableListOf<ByteArray>()
        val rx = MutableSharedFlow<IncomingPacket>()
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.Connected)
        override val incoming = rx
        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun send(packet: ByteArray) { sent += packet }
    }
}

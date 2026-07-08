package io.github.trevarj.motd.push

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hard acceptance gate: reproduce RFC 8291 Appendix A / Section 5 exactly.
 *
 * All constants below are transcribed verbatim from the RFC 8291 text
 * (https://www.rfc-editor.org/rfc/rfc8291.txt), Section 5 "Push Message Encryption"
 * example and Appendix A. Base64url, no padding.
 *
 * Decrypting the sample body with the receiver (user-agent) key material must yield the
 * ASCII string "When I grow up, I want to be a watermelon".
 */
class WebPushCryptoTest {

    // RFC 8291 §5: receiver (user agent) key material.
    private val uaPrivate = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val authSecret = "BTBZMqHH6r4Tts7J_aSIgg"

    // RFC 8291 §5: the full encrypted push message body (salt || rs || idlen || keyid || ciphertext),
    // reassembled from the three wrapped lines in the RFC with whitespace removed.
    private val encryptedBody =
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    private val expectedPlaintext = "When I grow up, I want to be a watermelon"

    private fun receiverKeys() = WebPushCrypto.KeyMaterial(
        privateKey = WebPushCrypto.decodeB64Url(uaPrivate),
        publicUncompressed = WebPushCrypto.decodeB64Url(uaPublic),
        auth = WebPushCrypto.decodeB64Url(authSecret),
    )

    @Test
    fun rfc8291_appendixA_vector_decrypts_to_watermelon() {
        val body = WebPushCrypto.decodeB64Url(encryptedBody)
        val plaintext = WebPushCrypto.decrypt(body, receiverKeys())
        assertEquals(expectedPlaintext, String(plaintext, Charsets.US_ASCII))
    }

    @Test
    fun encrypt_decrypt_round_trip_strips_padding() {
        val receiver = WebPushCrypto.generateKeyMaterial()
        val sender = WebPushCrypto.generateEcKeyPair()
        val salt = ByteArray(16) { it.toByte() }
        val message = "one line, no CRLF: hello 🍉".toByteArray(Charsets.UTF_8)

        val body = WebPushCrypto.encrypt(
            plaintext = message,
            salt = salt,
            recordSize = 4096,
            receiverPublic = receiver.publicUncompressed,
            receiverAuth = receiver.auth,
            senderKeys = sender,
        )
        val decrypted = WebPushCrypto.decrypt(body, receiver)
        assertArrayEquals(message, decrypted)
    }

    @Test
    fun uncompressed_point_round_trips() {
        val keys = WebPushCrypto.generateKeyMaterial()
        val reencoded = WebPushCrypto.encodeUncompressed(
            WebPushCrypto.decodeUncompressed(keys.publicUncompressed),
        )
        assertArrayEquals(keys.publicUncompressed, reencoded)
        assertEquals(65, keys.publicUncompressed.size)
        assertEquals(0x04, keys.publicUncompressed[0].toInt())
    }
}

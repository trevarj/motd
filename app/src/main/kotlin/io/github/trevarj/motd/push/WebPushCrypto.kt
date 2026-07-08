package io.github.trevarj.motd.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 8291 (`aes128gcm`) Web Push message decryption plus P-256 keypair / auth-secret
 * generation, using only JCA — no third-party crypto dependency.
 *
 * The content-coding header (RFC 8188) travels inside the message body:
 *
 *   header     = salt(16) || rs(4, big-endian) || idlen(1) || keyid(idlen)
 *   keyid      = sender uncompressed P-256 public key (65 bytes) for Web Push
 *   ciphertext = rest of the body (single record; a 16-byte GCM tag trails)
 */
object WebPushCrypto {
    private const val P256_CURVE = "secp256r1"
    private const val UNCOMPRESSED_POINT_LEN = 65 // 0x04 || X(32) || Y(32)
    private const val COORD_LEN = 32
    private const val AUTH_SECRET_LEN = 16
    private const val GCM_TAG_BITS = 128

    private val b64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val b64UrlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** Client (user-agent) key material persisted via PushPrefs; all fields base64url. */
    data class KeyMaterial(
        val privateKey: ByteArray, // raw P-256 scalar, big-endian, 32 bytes
        val publicUncompressed: ByteArray, // 65-byte uncompressed point
        val auth: ByteArray, // 16-byte auth secret
    )

    // --- base64url helpers ---------------------------------------------------

    fun decodeB64Url(s: String): ByteArray = b64UrlDecoder.decode(s.trim())

    fun encodeB64Url(b: ByteArray): String = b64UrlEncoder.encodeToString(b)

    // --- key generation ------------------------------------------------------

    /** Generate a fresh P-256 keypair and a random 16-byte auth secret. */
    fun generateKeyMaterial(random: SecureRandom = SecureRandom()): KeyMaterial {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(P256_CURVE), random)
        val pair = gen.generateKeyPair()
        val priv = pair.private as ECPrivateKey
        val pub = pair.public as ECPublicKey
        val auth = ByteArray(AUTH_SECRET_LEN).also { random.nextBytes(it) }
        return KeyMaterial(
            privateKey = fixedWidth(priv.s.toByteArray(), COORD_LEN),
            publicUncompressed = encodeUncompressed(pub),
            auth = auth,
        )
    }

    // --- uncompressed point encode / decode ---------------------------------

    /** Encode an EC public key as a 65-byte uncompressed point `0x04 || X || Y`. */
    fun encodeUncompressed(pub: ECPublicKey): ByteArray {
        val x = fixedWidth(pub.w.affineX.toByteArray(), COORD_LEN)
        val y = fixedWidth(pub.w.affineY.toByteArray(), COORD_LEN)
        val out = ByteArray(UNCOMPRESSED_POINT_LEN)
        out[0] = 0x04
        System.arraycopy(x, 0, out, 1, COORD_LEN)
        System.arraycopy(y, 0, out, 1 + COORD_LEN, COORD_LEN)
        return out
    }

    /** Rebuild an [ECPublicKey] on P-256 from a 65-byte uncompressed point. */
    fun decodeUncompressed(point: ByteArray): ECPublicKey {
        require(point.size == UNCOMPRESSED_POINT_LEN && point[0].toInt() == 0x04) {
            "expected 65-byte uncompressed P-256 point"
        }
        val x = BigInteger(1, point.copyOfRange(1, 1 + COORD_LEN))
        val y = BigInteger(1, point.copyOfRange(1 + COORD_LEN, UNCOMPRESSED_POINT_LEN))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params())
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun privateKeyFromScalar(scalar: ByteArray): ECPrivateKey {
        val s = BigInteger(1, scalar)
        val spec = ECPrivateKeySpec(s, p256Params())
        return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
    }

    /** Named-curve params for P-256, borrowed from a throwaway generated key. */
    private fun p256Params(): ECParameterSpec {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(P256_CURVE))
        return (gen.generateKeyPair().public as ECPublicKey).params
    }

    // --- HKDF (RFC 5869) over HmacSHA256 ------------------------------------

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand for outputs <= 32 bytes (a single HMAC block; sufficient for Web Push). */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..32) { "hkdfExpand length must be 1..32, was $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01) // T(1) counter
        return mac.doFinal().copyOf(length)
    }

    /** `"Content-Encoding: <coding>" || 0x00` info string (RFC 8188 §2.3). */
    private fun contentEncodingInfo(coding: String): ByteArray =
        concat("Content-Encoding: $coding".toByteArray(Charsets.US_ASCII), byteArrayOf(0x00))

    /** `"WebPush: info" || 0x00 || ua_public || as_public` (RFC 8291 §3.4). */
    private fun keyCombiningInfo(uaPublic: ByteArray, asPublic: ByteArray): ByteArray =
        concat("WebPush: info".toByteArray(Charsets.US_ASCII), byteArrayOf(0x00), uaPublic, asPublic)

    // --- decryption ----------------------------------------------------------

    /**
     * Decrypt an RFC 8291 `aes128gcm` Web Push body with the receiver's key material.
     * Returns the padding-stripped plaintext.
     */
    fun decrypt(body: ByteArray, keys: KeyMaterial): ByteArray {
        require(body.size >= 21) { "body too short for aes128gcm header" }
        val salt = body.copyOfRange(0, 16)
        // rs (record size, bytes 16..19) is not needed to decrypt a single record.
        val idlen = body[20].toInt() and 0xFF
        val headerLen = 21 + idlen
        require(body.size > headerLen) { "no ciphertext after header" }
        val asPub = body.copyOfRange(21, headerLen)
        val ciphertext = body.copyOfRange(headerLen, body.size)

        val plaintextPadded = decryptRecord(salt, asPub, ciphertext, keys)
        return stripPadding(plaintextPadded)
    }

    private fun decryptRecord(
        salt: ByteArray,
        asPub: ByteArray,
        ciphertext: ByteArray,
        keys: KeyMaterial,
    ): ByteArray {
        val (cek, nonce) = deriveKeyAndNonce(salt, asPub, keys.publicUncompressed, keys.auth) {
            ecdh(privateKeyFromScalar(keys.privateKey), decodeUncompressed(asPub))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    /**
     * RFC 8291 §3.4 key/nonce derivation. Shared by decrypt (receiver holds the private key)
     * and the test-only encrypt inverse (sender holds the private key); the ECDH secret is the
     * same either way, so it is supplied by the caller via [ecdhSecret].
     *
     * @param uaPublic receiver (user-agent) uncompressed public key
     * @param asPublic is derived by the caller and folded into key_info by the caller side;
     *   here only uaPublic/asPublic ordering matters — see [keyCombiningInfo].
     */
    private inline fun deriveKeyAndNonce(
        salt: ByteArray,
        asPublic: ByteArray,
        uaPublic: ByteArray,
        auth: ByteArray,
        ecdhSecret: () -> ByteArray,
    ): Pair<ByteArray, ByteArray> {
        // PRK_key = HKDF-Extract(auth_secret, ecdh_secret)
        val prkKey = hkdfExtract(auth, ecdhSecret())
        // IKM = HKDF-Expand(PRK_key, "WebPush: info" || 0x00 || ua_public || as_public, 32)
        val ikm = hkdfExpand(prkKey, keyCombiningInfo(uaPublic, asPublic), 32)
        // PRK = HKDF-Extract(salt, IKM)
        val prk = hkdfExtract(salt, ikm)
        val cek = hkdfExpand(prk, contentEncodingInfo("aes128gcm"), 16)
        val nonce = hkdfExpand(prk, contentEncodingInfo("nonce"), 12)
        return cek to nonce
    }

    private fun ecdh(priv: ECPrivateKey, pub: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** Record padding: `plaintext || 0x02 || 0x00*` — strip the delimiter and trailing zeros. */
    private fun stripPadding(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i].toInt() == 0x00) i--
        require(i >= 0 && padded[i].toInt() == 0x02) { "missing 0x02 padding delimiter" }
        return padded.copyOfRange(0, i)
    }

    // --- inverse (encryption) — package-visible, for tests only --------------

    /**
     * RFC 8291 `aes128gcm` encryption inverse. Used only by tests to exercise the padding
     * strip path via an encrypt -> decrypt round-trip. [senderKeys] supplies the ephemeral
     * application-server keypair (its uncompressed public key becomes the header `keyid`).
     */
    internal fun encrypt(
        plaintext: ByteArray,
        salt: ByteArray,
        recordSize: Int,
        receiverPublic: ByteArray,
        receiverAuth: ByteArray,
        senderKeys: KeyPair,
    ): ByteArray {
        val senderPub = encodeUncompressed(senderKeys.public as ECPublicKey)
        // uaPublic = receiver, asPublic = sender — same ordering the decrypt side expects.
        val (cek, nonce) = deriveKeyAndNonce(salt, senderPub, receiverPublic, receiverAuth) {
            ecdh(senderKeys.private as ECPrivateKey, decodeUncompressed(receiverPublic))
        }

        val padded = concat(plaintext, byteArrayOf(0x02)) // last-record delimiter, no extra zeros
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(padded)

        // header = salt(16) || rs(4 BE) || idlen(1) || keyid(65)
        val header = ByteArray(21 + senderPub.size)
        System.arraycopy(salt, 0, header, 0, 16)
        header[16] = (recordSize ushr 24).toByte()
        header[17] = (recordSize ushr 16).toByte()
        header[18] = (recordSize ushr 8).toByte()
        header[19] = recordSize.toByte()
        header[20] = senderPub.size.toByte()
        System.arraycopy(senderPub, 0, header, 21, senderPub.size)
        return concat(header, ct)
    }

    /** Generate a raw P-256 KeyPair (JCA objects) — for the test encrypt path. */
    internal fun generateEcKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(P256_CURVE))
        return gen.generateKeyPair()
    }

    // --- byte utils ----------------------------------------------------------

    /** Left-pad or trim a two's-complement magnitude to exactly [width] big-endian bytes. */
    private fun fixedWidth(bytes: ByteArray, width: Int): ByteArray {
        if (bytes.size == width) return bytes
        val out = ByteArray(width)
        if (bytes.size > width) {
            // BigInteger.toByteArray may add a leading 0x00 sign byte; drop the excess prefix.
            System.arraycopy(bytes, bytes.size - width, out, 0, width)
        } else {
            System.arraycopy(bytes, 0, out, width - bytes.size, bytes.size)
        }
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }
}

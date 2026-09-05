package io.github.trevarj.motd.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceEncryptionResult(
    val file: File,
    val keyFragment: String,
    val mimeType: String = "application/vnd.motd.voice",
)

internal enum class VoiceCryptoFailureKind {
    INVALID_KEY,
    INVALID_PAYLOAD,
    AUTHENTICATION_FAILED,
}

internal class VoiceCryptoException(
    val kind: VoiceCryptoFailureKind,
) : Exception(kind.name)

@Singleton
class VoiceCrypto
    @Inject
    constructor(
        private val cacheStore: AudioCacheStore,
    ) {
        private val random = SecureRandom()

        fun encrypt(input: File): VoiceEncryptionResult {
            val keyBytes = ByteArray(KEY_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(AAD)
            val output = cacheStore.tempFile("voice-encrypted-", ".motdvoice")
            output.outputStream().use { out ->
                out.write(MAGIC)
                out.write(nonce)
                input.inputStream().use { source ->
                    javax.crypto.CipherOutputStream(out, cipher).use { encrypted ->
                        source.copyTo(encrypted)
                    }
                }
            }
            return VoiceEncryptionResult(
                file = output,
                keyFragment = "motd-key=${B64.encodeToString(keyBytes)}",
            )
        }

        suspend fun decrypt(
            input: File,
            keyFragment: String,
            output: File,
        ): File =
            withContext(Dispatchers.IO) {
                try {
                    val key = parseKey(keyFragment)
                    input.inputStream().use { source ->
                        val header = ByteArray(MAGIC.size)
                        if (!source.readExact(header) || !header.contentEquals(MAGIC)) {
                            throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_PAYLOAD)
                        }
                        val nonce = ByteArray(NONCE_BYTES)
                        if (!source.readExact(nonce)) {
                            throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_PAYLOAD)
                        }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                        cipher.updateAAD(AAD)
                        output.outputStream().use { sink ->
                            val buffer = ByteArray(CRYPTO_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                val decrypted = cipher.update(buffer, 0, count)
                                currentCoroutineContext().ensureActive()
                                if (decrypted != null && decrypted.isNotEmpty()) sink.write(decrypted)
                            }
                            currentCoroutineContext().ensureActive()
                            val finalBytes = cipher.doFinal()
                            currentCoroutineContext().ensureActive()
                            if (finalBytes.isNotEmpty()) sink.write(finalBytes)
                        }
                    }
                    output
                } catch (cancelled: CancellationException) {
                    output.delete()
                    throw cancelled
                } catch (error: VoiceCryptoException) {
                    output.delete()
                    throw error
                } catch (_: AEADBadTagException) {
                    output.delete()
                    throw VoiceCryptoException(VoiceCryptoFailureKind.AUTHENTICATION_FAILED)
                } catch (_: BadPaddingException) {
                    output.delete()
                    throw VoiceCryptoException(VoiceCryptoFailureKind.AUTHENTICATION_FAILED)
                } catch (_: IllegalBlockSizeException) {
                    output.delete()
                    throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_PAYLOAD)
                } catch (error: Exception) {
                    output.delete()
                    throw error
                }
            }

        private suspend fun InputStream.readExact(bytes: ByteArray): Boolean {
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = read(bytes, offset, bytes.size - offset)
                if (count < 0) return false
                if (count > 0) offset += count
            }
            return true
        }

        private fun parseKey(fragment: String): ByteArray {
            val value =
                fragment
                    .split('&')
                    .firstOrNull { it.substringBefore('=') == "motd-key" }
                    ?.substringAfter('=', "")
                    ?.takeIf(String::isNotBlank)
                    ?: throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_KEY)
            val key =
                try {
                    B64_DECODER.decode(value)
                } catch (_: IllegalArgumentException) {
                    throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_KEY)
                }
            if (key.size != KEY_BYTES) throw VoiceCryptoException(VoiceCryptoFailureKind.INVALID_KEY)
            return key
        }

        companion object {
            private const val KEY_BYTES = 32
            private const val NONCE_BYTES = 12
            private const val GCM_TAG_BITS = 128
            private const val CRYPTO_BUFFER_BYTES = 32 * 1024
            private val B64 = Base64.getUrlEncoder().withoutPadding()
            private val B64_DECODER = Base64.getUrlDecoder()
            private val MAGIC = byteArrayOf('M'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte(), 'D'.code.toByte(), 'V'.code.toByte(), 1)
            private val AAD = "motd-voice-v1".toByteArray(Charsets.UTF_8)
        }
    }

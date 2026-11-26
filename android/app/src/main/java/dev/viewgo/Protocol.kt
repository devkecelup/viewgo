package dev.viewgo

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Wire format lives in docs/PROTOCOL.md. */
object Proto {
    const val VERSION = 1
    const val PBKDF2_ITER = 120_000
    const val MAX_BODY = 16 * 1024 * 1024
    const val MAX_PLAIN = 4096

    const val T_VIDEO_CONFIG = 0x01
    const val T_VIDEO_FRAME = 0x02
    const val T_CONTROL = 0x03
    const val T_LOG = 0x04
    const val T_AUDIO_CONFIG = 0x05
    const val T_AUDIO_FRAME = 0x06
    const val T_STOP = 0x07

    const val STATUS_OK = 0
    const val STATUS_EXPIRED = 2
    const val STATUS_RATE_LIMITED = 3
    const val STATUS_BUSY = 4

    val MAGIC = byteArrayOf('V'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte())
}

/** Normalises user input: uppercase, strips separators, maps Crockford look-alikes. */
fun normalizeCode(input: String): String? {
    val out = StringBuilder()
    for (raw in input.uppercase()) {
        if (raw == '-' || raw == ' ') continue
        val ch = when (raw) {
            'I', 'L' -> '1'
            'O' -> '0'
            else -> raw
        }
        if (ch !in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") return null
        out.append(ch)
    }
    return if (out.length == 9) out.toString() else null
}

internal fun deriveKey(code: String, salt: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray): ByteArray {
    val fullSalt = salt + clientRandom + serverRandom
    val spec = PBEKeySpec(code.toCharArray(), fullSalt, Proto.PBKDF2_ITER, 256)
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
}

internal fun proof(key: ByteArray, salt: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update("viewgo-auth".toByteArray())
    mac.update(salt)
    mac.update(clientRandom)
    mac.update(serverRandom)
    return mac.doFinal()
}

/**
 * Authenticated session to the Windows receiver: pairing handshake, then AES-256-GCM records.
 */
class Link(private val socket: Socket, private val code: String) {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private lateinit var key: ByteArray
    private var sendCounter = 0L
    private var recvCounter = 0L

    fun handshake() {
        val clientRandom = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hello = ByteArray(21)
        System.arraycopy(Proto.MAGIC, 0, hello, 0, 4)
        hello[4] = Proto.VERSION.toByte()
        System.arraycopy(clientRandom, 0, hello, 5, 16)
        writePlain(hello)

        val challenge = readPlain()
        val magicOk = challenge.size >= 4 && (0 until 4).all { challenge[it] == Proto.MAGIC[it] }
        if (challenge.size < 38 || !magicOk || challenge[4].toInt() != Proto.VERSION) {
            throw IOException("Unexpected reply from the laptop.")
        }
        when (challenge[5].toInt() and 0xFF) {
            Proto.STATUS_OK -> Unit
            Proto.STATUS_EXPIRED -> throw IOException("The code expired. Use the new code shown on the laptop.")
            Proto.STATUS_RATE_LIMITED -> throw IOException("Too many attempts. Wait a minute, then try again.")
            Proto.STATUS_BUSY -> throw IOException("The laptop is already mirroring another phone.")
            else -> throw IOException("The laptop rejected the connection.")
        }

        val salt = challenge.copyOfRange(6, 22)
        val serverRandom = challenge.copyOfRange(22, 38)
        key = deriveKey(code, salt, clientRandom, serverRandom)

        val auth = ByteArray(37)
        System.arraycopy(Proto.MAGIC, 0, auth, 0, 4)
        auth[4] = Proto.VERSION.toByte()
        System.arraycopy(proof(key, salt, clientRandom, serverRandom), 0, auth, 5, 32)
        writePlain(auth)

        val ok = openRecord() ?: throw IOException("The laptop could not prove the session key.")
        if (ok.size != 5 || ok[0].toInt() != 0x10) throw IOException("The laptop could not prove the session key.")
    }

    fun send(type: Int, body: ByteArray) {
        val plain = ByteArray(1 + body.size)
        plain[0] = type.toByte()
        System.arraycopy(body, 0, plain, 1, body.size)
        synchronized(output) {
            val record = seal(plain)
            output.writeInt(record.size)
            output.write(record)
            output.flush()
        }
    }

    fun sendStop() = runCatching { send(Proto.T_STOP, ByteArray(0)) }

    fun sendConfig(type: Int, body: ByteArray) = send(type, body)

    fun close() = runCatching { socket.close() }

    private fun writePlain(body: ByteArray) {
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    private fun readPlain(): ByteArray {
        val length = input.readInt()
        if (length <= 0 || length > Proto.MAX_PLAIN) throw IOException("Invalid handshake length.")
        return ByteArray(length).also { input.readFully(it) }
    }

    private fun openRecord(): ByteArray? {
        val length = input.readInt()
        if (length <= 0 || length > Proto.MAX_BODY) throw IOException("Invalid record length.")
        val record = ByteArray(length).also { input.readFully(it) }
        if (record.size < 28 || record[0].toInt() != 1) return null
        var counter = 0L
        for (i in 0 until 8) counter = (counter shl 8) or (record[4 + i].toLong() and 0xFF)
        if (counter != recvCounter) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, record.copyOfRange(0, 12)))
        val plain = runCatching { cipher.doFinal(record, 12, record.size - 12) }.getOrNull() ?: return null
        recvCounter++
        return plain
    }

    private fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12)
        iv[0] = 0
        for (i in 0 until 8) iv[4 + i] = ((sendCounter ushr (56 - 8 * i)) and 0xFF).toByte()
        sendCounter++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }
}

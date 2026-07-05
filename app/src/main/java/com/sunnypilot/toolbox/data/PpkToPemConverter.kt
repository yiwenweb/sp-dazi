package com.sunnypilot.toolbox.data

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import android.util.Base64

/**
 * Converts an unencrypted PuTTY PPK (v2) private key to the OpenSSH PEM
 * (PKCS#1) format that JSch can consume.
 *
 * Supported: PPK v2 with algorithm "ssh-rsa" and no passphrase.
 * Not supported: encrypted PPK files, PPK v3, Ed25519 (for now).
 */
object PpkToPemConverter {

    fun convertIfNeeded(keyContent: String): String {
        val trimmed = keyContent.trim()
        if (trimmed.startsWith("PuTTY-User-Key-File-")) {
            return convertToOpenSshPem(trimmed)
        }
        return trimmed
    }

    private fun convertToOpenSshPem(ppkContent: String): String {
        val lines = ppkContent.lines()
        val headers = mutableMapOf<String, String>()
        val publicLines = StringBuilder()
        val privateLines = StringBuilder()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            when {
                line.startsWith("Public-Lines:") -> {
                    val count = line.substringAfter("Public-Lines:").trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Public-Lines value")
                    i++
                    repeat(count) {
                        if (i < lines.size) publicLines.append(lines[i].trim())
                        i++
                    }
                    continue
                }
                line.startsWith("Private-Lines:") -> {
                    val count = line.substringAfter("Private-Lines:").trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Private-Lines value")
                    i++
                    repeat(count) {
                        if (i < lines.size) privateLines.append(lines[i].trim())
                        i++
                    }
                    continue
                }
                line.contains(":") -> {
                    val key = line.substringBefore(":").trim()
                    val value = line.substringAfter(":").trim()
                    headers[key] = value
                }
            }
            i++
        }

        val encryption = headers["Encryption"] ?: "none"
        if (encryption != "none") {
            throw IllegalArgumentException(
                "暂不支持加密的 PPK 文件，请先在 PuTTYgen 中移除密码或转换为 OpenSSH 格式"
            )
        }

        val algo = headers["PuTTY-User-Key-File-2"] ?: headers["PuTTY-User-Key-File-3"]
            ?: throw IllegalArgumentException("无法识别 PPK 文件版本")
        if (algo != "ssh-rsa") {
            throw IllegalArgumentException("暂不支持 PPK 算法: $algo，当前仅支持 ssh-rsa")
        }

        val publicBase64 = publicLines.toString().ifBlank {
            throw IllegalArgumentException("缺少 Public-Lines")
        }
        val privateBase64 = privateLines.toString().ifBlank {
            throw IllegalArgumentException("缺少 Private-Lines")
        }

        val publicBytes = base64Decode(publicBase64)
        val privateBytes = base64Decode(privateBase64)

        // Public key: string "ssh-rsa", mpint e, mpint n
        val pubStream = DataInputStream(ByteArrayInputStream(publicBytes))
        val keyType = readString(pubStream)
        if (keyType != "ssh-rsa") {
            throw IllegalArgumentException("公钥算法不匹配: $keyType")
        }
        val publicExponent = readMpint(pubStream)
        val modulus = readMpint(pubStream)

        // Private key: mpint d, mpint p, mpint q, mpint iqmp
        val privStream = DataInputStream(ByteArrayInputStream(privateBytes))
        val privateExponent = readMpint(privStream)
        val primeP = readMpint(privStream)
        val primeQ = readMpint(privStream)
        val coefficient = readMpint(privStream)

        // Compute CRT parameters
        val primeExponentP = privateExponent.mod(primeP.subtract(BigInteger.ONE))
        val primeExponentQ = privateExponent.mod(primeQ.subtract(BigInteger.ONE))

        // Encode PKCS#1 RSAPrivateKey
        val rsaSequence = encodeSequence(
            encodeInteger(BigInteger.ZERO), // version
            encodeInteger(modulus),
            encodeInteger(publicExponent),
            encodeInteger(privateExponent),
            encodeInteger(primeP),
            encodeInteger(primeQ),
            encodeInteger(primeExponentP),
            encodeInteger(primeExponentQ),
            encodeInteger(coefficient)
        )

        val base64 = Base64.encodeToString(rsaSequence, Base64.DEFAULT)
        return buildString {
            appendLine("-----BEGIN RSA PRIVATE KEY-----")
            for (chunk in base64.chunked(64)) {
                appendLine(chunk)
            }
            appendLine("-----END RSA PRIVATE KEY-----")
        }
    }

    private fun readString(stream: DataInputStream): String {
        val length = stream.readInt()
        if (length < 0 || length > 1024) {
            throw IllegalArgumentException("Invalid string length: $length")
        }
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readMpint(stream: DataInputStream): BigInteger {
        val length = stream.readInt()
        if (length < 0 || length > 8192) {
            throw IllegalArgumentException("Invalid mpint length: $length")
        }
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return BigInteger(1, bytes)
    }

    private fun base64Decode(input: String): ByteArray {
        return Base64.decode(input.filter { !it.isWhitespace() }, Base64.DEFAULT)
    }

    private fun encodeInteger(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        // For positive integers, DER requires a leading 0x00 if the high bit is set
        if (bytes.isNotEmpty() && bytes[0].toInt() and 0x80 != 0) {
            bytes = byteArrayOf(0) + bytes
        }
        return wrap(0x02, bytes)
    }

    private fun encodeSequence(vararg children: ByteArray): ByteArray {
        val content = children.fold(byteArrayOf()) { acc, child -> acc + child }
        return wrap(0x30, content)
    }

    private fun wrap(tag: Int, content: ByteArray): ByteArray {
        val length = encodeLength(content.size)
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 128) {
            byteArrayOf(length.toByte())
        } else {
            val bytes = mutableListOf<Byte>()
            var remaining = length
            while (remaining > 0) {
                bytes.add(0, (remaining and 0xFF).toByte())
                remaining = remaining ushr 8
            }
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }
    }
}

package com.sunnypilot.toolbox.ui.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.NetworkInterface
import java.util.Collections
import java.util.EnumMap

object QrCodeUtil {

    fun generateQrCode(content: String, sizePx: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    fun getLocalIpAddress(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .filter { isPrivateAddress(it) }
                .firstOrNull()
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateAddress(address: java.net.Inet4Address): Boolean {
        val octets = address.hostAddress?.split(".")?.mapNotNull { it.toIntOrNull() } ?: return false
        if (octets.size != 4) return false
        val (a, b, _, _) = octets
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }
}

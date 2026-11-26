package cc.ortus.kit

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A verification's QR code holds binary data: read as bytes, they come back whole. */
class QrCodeTest {
    /** A frame's brightness plane showing `text`'s code (ISO-8859-1: one byte a character). */
    private fun frame(text: String, size: Int = 400): ByteArray {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return ByteArray(size * size) { i -> if (matrix[i % size, i / size]) 0 else 0xff.toByte() }
    }

    /**
     * The bytes of the data codewords' one byte-mode segment (after an ECI
     * designator, if there is one), as the end-to-end library reads them.
     */
    private fun payload(codewords: ByteArray): ByteArray {
        var at = 0
        fun read(n: Int): Int = (0 until n).fold(0) { acc, _ ->
            val bit = (codewords[at / 8].toInt() shr (7 - at % 8)) and 1
            at++
            (acc shl 1) or bit
        }
        var mode = read(4)
        if (mode == 0b0111) { read(8); mode = read(4) }
        check(mode == 0b0100) { "not byte mode: $mode" }
        val count = read(8)
        return ByteArray(count) { read(8).toByte() }
    }

    @Test fun binaryBytesComeBackWhole() {
        // Like a verification's: MATRIX, then bytes no text keeps (zero, above 0x7f).
        val sent = "MATRIX".toByteArray() + byteArrayOf(0x02, 0x00, 0x00, 0x20) + ByteArray(96) { (it * 37 + 128).toByte() }
        val raw = assertNotNull(QrCode.readBytes(frame(String(sent, Charsets.ISO_8859_1)), 400, 400))
        assertContentEquals(sent, payload(raw))
    }

    @Test fun noCodeNoBytes() {
        assertNull(QrCode.readBytes(ByteArray(100 * 100) { 0xff.toByte() }, 100, 100))
    }
}

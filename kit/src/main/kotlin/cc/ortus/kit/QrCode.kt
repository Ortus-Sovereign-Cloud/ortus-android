package cc.ortus.kit

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reading a QR code from a camera frame: an invitation's text, or a verification's bytes. */
object QrCode {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    /**
     * The text of the QR code in a frame's brightness plane (the camera's Y
     * plane: `width` by `height` pixels, `rowStride` bytes a row), if it
     * holds one.
     */
    fun read(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? =
        decode(luma, width, height, rowStride)?.text

    /**
     * The data codewords of the QR code in a frame (ZXing's raw bytes: the
     * segments' modes and counts with their bytes), if it holds one. A code
     * of binary data (a verification's) is read this way: its text would
     * not keep the bytes.
     */
    fun readBytes(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): ByteArray? =
        decode(luma, width, height, rowStride)?.rawBytes

    private fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): com.google.zxing.Result? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints) }.getOrNull()
    }

    /** An Ortus invitation in a frame, if it shows one. */
    fun invitation(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): InvitationLink? =
        read(luma, width, height, rowStride)?.let { InvitationLink.parse(it) }
}

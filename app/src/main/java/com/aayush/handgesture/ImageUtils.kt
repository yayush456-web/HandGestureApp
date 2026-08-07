package com.aayush.handgesture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        return bmp
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yPlane.buffer.get(nv21, 0, ySize)

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val vPixelStride = vPlane.pixelStride

        // Common fast path: interleaved VU (pixelStride 2) - typical for NV21-like layout.
        return if (vPixelStride == 2 && uPlane.pixelStride == 2) {
            vBuffer.get(nv21, ySize, vSize)
            nv21
        } else {
            // Fallback: manually interleave U/V per pixel (slower, safe for all devices).
            var pos = ySize
            val uBytes = ByteArray(uSize)
            val vBytes = ByteArray(vSize)
            uBuffer.get(uBytes)
            vBuffer.get(vBytes)
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val chromaWidth = image.width / 2
            val chromaHeight = image.height / 2
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val uIndex = row * uRowStride + col * uPixelStride
                    val vIndex = row * uRowStride + col * uPixelStride
                    if (pos + 1 < nv21.size && vIndex < vBytes.size && uIndex < uBytes.size) {
                        nv21[pos++] = vBytes[vIndex]
                        nv21[pos++] = uBytes[uIndex]
                    }
                }
            }
            nv21
        }
    }
}

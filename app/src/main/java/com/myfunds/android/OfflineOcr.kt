package com.myfunds.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.myfunds.core.OcrLine
import com.myfunds.core.readBounded
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** The only owner of TessBaseAPI and all Bitmap objects is the calling worker. */
class OfflineOcr(private val context: Context) {
    companion object {
        const val MODEL_SHA = "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730"
        private val modelLock = Any()
    }
    fun recognize(uri: Uri, cancelled: AtomicBoolean, progress: (String) -> Unit): List<OcrLine> {
        fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("已取消识别") }
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, 16 * 1024 * 1024) } ?: throw IllegalArgumentException("无法打开所选图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..80000 && bounds.outWidth.toLong() * bounds.outHeight <= 160000000L) { "图片过大或格式不支持，请裁短截图后重试" }
        checkCancelled(); progress("正在准备本机中文识别…")
        val dataDir = File(context.filesDir, "ocr")
        val dataFile = File(dataDir, "tessdata/chi_sim.traineddata")
        fun sha(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(16384); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
        synchronized(modelLock) { if (!dataFile.isFile || sha(dataFile) != MODEL_SHA) {
            dataFile.parentFile!!.mkdirs(); val pending = File(dataFile.parentFile, "chi_sim.pending")
            try { context.assets.open("tessdata/chi_sim.traineddata").use { input -> pending.outputStream().use { input.copyTo(it) } }; check(sha(pending) == MODEL_SHA) { "本机识别模型损坏，请重新安装应用" }; check(pending.renameTo(dataFile)) { "无法准备本机识别模型" } }
            finally { pending.delete() }
        } }
        checkCancelled()
        val tess = TessBaseAPI()
        val lines = mutableListOf<OcrLine>()
        fun readBitmap(bitmap: Bitmap, offset: Int) {
            checkCancelled(); tess.setImage(bitmap); tess.getUTF8Text(); checkCancelled()
            val iterator = tess.resultIterator ?: return
            try {
                iterator.begin()
                do {
                    checkCancelled()
                    val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                    val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (text.isNotBlank() && box.size == 4) lines.add(OcrLine(text, box[0], box[1] + offset, box[2], box[3] + offset))
                    require(lines.size <= 10000) { "截图过长，请分成几张识别" }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally { iterator.delete() }
            tess.clear()
        }
        try {
            check(tess.init(dataDir.absolutePath, "chi_sim", TessBaseAPI.OEM_LSTM_ONLY)) { "中文识别模型初始化失败" }
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            tess.setVariable("preserve_interword_spaces", "1")
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            if (orientation > ExifInterface.ORIENTATION_NORMAL) {
                var sample = 1
                while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 2800000) sample *= 2
                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IllegalArgumentException("图片解码失败")
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> { setRotate(180f); postScale(-1f, 1f) }
                        5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
                    }
                }
                var rotated: Bitmap? = null
                try { rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true); progress("正在本机识别…"); readBitmap(rotated, 0) }
                finally { if (rotated !== original) rotated?.recycle(); original.recycle() }
            } else {
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: throw IllegalArgumentException("图片格式不支持")
                try {
                    var sample = 1; while (bounds.outWidth / sample > 1600) sample *= 2
                    val tileHeight = 1800 * sample; val step = 1640 * sample
                    var top = 0; var part = 0
                    while (top < bounds.outHeight) {
                        checkCancelled(); part++; progress("正在本机识别第 $part 段…")
                        val bottom = minOf(top + tileHeight, bounds.outHeight)
                        val bitmap = decoder.decodeRegion(Rect(0, top, bounds.outWidth, bottom), BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: throw IllegalArgumentException("图片解码失败")
                        try { readBitmap(bitmap, top / sample) } finally { bitmap.recycle() }
                        if (bottom == bounds.outHeight) break
                        top += step
                    }
                } finally { decoder.recycle() }
            }
            checkCancelled()
            return lines.distinctBy { it.text to (it.top / 16) }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        } finally { tess.recycle() }
    }
}

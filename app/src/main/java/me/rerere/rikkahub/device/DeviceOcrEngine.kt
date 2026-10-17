/*
 * 设备控制·文字识别（离线）——等价 Reticle 的 OcrEng.java。
 * 能力改写自 Reticle (https://github.com/Bumfuzzle-app/reticle)
 * Copyright 2026 Bumfuzzle, Apache License 2.0（已改写为 Kotlin）。
 * 许可全文见仓库根目录 NOTICE 与 LICENSES/Apache-2.0.txt。
 */
package me.rerere.rikkahub.device

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 识别出的一行文字；[box] 用整帧像素坐标，[centerX]/[centerY] 是可直接 tap 的中心点。 */
data class DeviceOcrLine(
    val text: String,
    val box: Rect,
    val centerX: Int,
    val centerY: Int,
)

/**
 * ML Kit 中文文字识别（bundled 离线版，模型随应用打包，不联网、不依赖 GMS）。
 *
 * 同一时刻只持有一个识别器实例；内存自保到「收」档时用 [drop] 放掉（几十 MB 级，下次用自动重建）。
 * 所有方法都是阻塞的，调用方负责放到 IO 线程。
 */
object DeviceOcrEngine {

    @Volatile
    private var recognizer: TextRecognizer? = null

    private val lock = Any()

    private fun recognizer(): TextRecognizer = synchronized(lock) {
        recognizer ?: TextRecognition
            .getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { recognizer = it }
    }

    /**
     * 识别 [bitmap]。给了 [region]（整帧像素）就只认这一块，返回的坐标仍换算回整帧。
     * 单行没有边界框的（识别不出形状）直接跳过，避免把坏坐标喂给点击。
     */
    fun recognize(bitmap: Bitmap, region: Rect? = null): List<DeviceOcrLine> {
        val raw = runCatching {
            val (input, offsetX, offsetY, frameWidth, frameHeight) = prepare(bitmap, region)
            val text: Text = Tasks.await(recognizer().process(InputImage.fromBitmap(input, 0)))
            text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val value = line.text
                    if (value.isBlank()) return@mapNotNull null
                    val full = Rect(
                        box.left + offsetX,
                        box.top + offsetY,
                        box.right + offsetX,
                        box.bottom + offsetY,
                    )
                    DeviceOcrLine(
                        text = value,
                        box = full,
                        centerX = full.centerX().coerceIn(0, frameWidth - 1),
                        centerY = full.centerY().coerceIn(0, frameHeight - 1),
                    )
                }
            }
        }.getOrElse { throwable ->
            throw IllegalStateException("OCR failed: ${throwable.message}", throwable)
        }
        return raw
    }

    /** 只要一段合并文本（块级拼接，行间换行），用于 human 可读的整屏读取。 */
    fun recognizeText(bitmap: Bitmap, region: Rect? = null): String = recognize(bitmap, region)
        .joinToString("\n") { it.text }

    /** 内存自保·二级「收」：放掉识别器（下次用自动重建）。 */
    fun drop() {
        synchronized(lock) {
            runCatching { recognizer?.close() }
            recognizer = null
        }
    }

    private data class Prepared(
        val input: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    )

    private fun prepare(bitmap: Bitmap, region: Rect?): Prepared {
        if (region == null) {
            return Prepared(bitmap, 0, 0, bitmap.width, bitmap.height)
        }
        val left = region.left.coerceIn(0, bitmap.width - 1)
        val top = region.top.coerceIn(0, bitmap.height - 1)
        val right = region.right.coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return Prepared(crop, left, top, bitmap.width, bitmap.height)
    }
}

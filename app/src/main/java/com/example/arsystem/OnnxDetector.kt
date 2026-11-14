package com.example.arsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import org.json.JSONObject

data class ModelDetection(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String, val confidence: Float)

/** Runs the exported YOLO11 detector locally when the ONNX asset is present. */
class OnnxDetector(private val context: Context) {
    private val labels = loadLabels()
    private val environment = OrtEnvironment.getEnvironment()
    private fun loadSession(assetName: String): OrtSession? = runCatching {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        environment.createSession(bytes, OrtSession.SessionOptions())
    }.getOrNull()
    private val session: OrtSession? = loadSession("models/equipment_detector.onnx")
    // A small auxiliary detector is kept separate from the equipment classifier.
    // It can be updated as defect annotations grow without changing equipment labels.
    private val defectSession: OrtSession? = loadSession("models/defect_detector.onnx")
    private val defectClassLabels = listOf("Возможная утечка", "Возможная коррозия", "След перегрева")

    fun isAvailable(): Boolean = session != null

    /** Reject blank, covered-lens, or otherwise textureless frames before inference. */
    fun isUsableFrame(bitmap: Bitmap): Boolean {
        if (bitmap.width < 32 || bitmap.height < 32) return false
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = (((pixel shr 16) and 255) * 0.299 + ((pixel shr 8) and 255) * 0.587 + (pixel and 255) * 0.114)
                sum += gray
                sumSquares += gray * gray
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val mean = sum / count
        val standardDeviation = kotlin.math.sqrt((sumSquares / count - mean * mean).coerceAtLeast(0.0))
        return standardDeviation >= 3.0 && mean in 8.0..248.0
    }

    private fun loadLabels(): List<String> {
        // Keep this fallback identical to the exported model. The current
        // trained checkpoint is the focused seven-class model; using the old
        // universal list would shift defect class indices and show defects as
        // unrelated equipment types on the phone.
        val fallback = listOf("pump", "motor", "valve", "pipe", "possible_leak", "possible_corrosion", "possible_overheat_mark")
        return runCatching {
            val json = JSONObject(context.assets.open("models/classes.json").use { it.bufferedReader().readText() })
            (0 until json.length()).map { index -> json.optString(index.toString(), fallback.getOrElse(index) { "class_$index" }) }
        }.getOrDefault(fallback)
            .map { label ->
                when (label) {
                    "pump" -> "Насос"; "motor" -> "Электродвигатель"; "gearbox" -> "Редуктор"; "fan" -> "Вентилятор"; "valve" -> "Клапан / задвижка"; "tank" -> "Резервуар"; "pipe" -> "Трубопровод"; "control_cabinet" -> "Шкаф управления"; "sensor" -> "Датчик КИПиА"; "possible_leak" -> "Возможная утечка"; "possible_corrosion" -> "Возможная коррозия"; "possible_overheat_mark" -> "След перегрева"; "inspection_zone" -> "Зона осмотра"; else -> label
                }
            }
    }

    fun detect(bitmap: Bitmap, threshold: Float = 0.45f): List<ModelDetection> {
        val fullFrame = detectSingle(bitmap, threshold, session, labels)
        val auxiliaryDefects = defectSession?.let {
            detectSingle(bitmap, threshold.coerceAtLeast(0.65f), it, defectClassLabels)
        }.orEmpty()
        val frameArea = bitmap.width.toFloat() * bitmap.height.toFloat()
        fun areaRatio(item: ModelDetection): Float =
            ((item.x2 - item.x1).coerceAtLeast(0f) * (item.y2 - item.y1).coerceAtLeast(0f)) / frameArea.coerceAtLeast(1f)

        // The supplied case labels contain almost-full-frame boxes. If every
        // equipment prediction has that shape, retry on overlapping quadrants and
        // map the local predictions back to the original image. This gives the UI
        // a useful object position while keeping the original model fully offline.
        val hasCompactEquipment = fullFrame.any {
            it.label !in defectLabels && areaRatio(it) in 0.03f..0.78f
        }
        if (hasCompactEquipment) return suppressOverlaps(fullFrame + auxiliaryDefects).take(8)

        val dominantClass = fullFrame
            .filter { it.label !in defectLabels }
            .maxByOrNull { it.confidence }
            ?.label
        if (dominantClass == null) return suppressOverlaps(fullFrame + auxiliaryDefects).take(8)

        val tiled = mutableListOf<ModelDetection>()
        val tileWidth = (bitmap.width * 0.68f).toInt().coerceIn(96, bitmap.width)
        val tileHeight = (bitmap.height * 0.68f).toInt().coerceIn(96, bitmap.height)
        val xPositions = listOf(0, (bitmap.width - tileWidth) / 2, bitmap.width - tileWidth).distinct()
        val yPositions = listOf(0, (bitmap.height - tileHeight) / 2, bitmap.height - tileHeight).distinct()
        for (top in yPositions) for (left in xPositions) {
            val tile = Bitmap.createBitmap(bitmap, left, top, tileWidth, tileHeight)
            val tileDetections = detectSingle(tile, threshold.coerceAtLeast(0.50f), session, labels)
            tileDetections
                // The case labels are often almost the entire tile. Keep those
                // responses when they come from overlapping tiles: mapping them
                // back to the source frame gives a useful, bounded inspection
                // region instead of a box around the whole result image.
                .filter { it.label == dominantClass && areaRatioInTile(it, tileWidth, tileHeight) in 0.20f..0.99f }
                .forEach { item ->
                    tiled += item.copy(
                        x1 = (left + item.x1).coerceIn(0f, bitmap.width.toFloat()),
                        y1 = (top + item.y1).coerceIn(0f, bitmap.height.toFloat()),
                        x2 = (left + item.x2).coerceIn(0f, bitmap.width.toFloat()),
                        y2 = (top + item.y2).coerceIn(0f, bitmap.height.toFloat()),
                        confidence = (item.confidence * 0.92f).coerceIn(0f, 1f)
                    )
                }
        }
        val localized = suppressOverlaps(tiled)
            .filter { areaRatio(it) in 0.03f..0.86f }
            .take(8)
        val compactDefects = (fullFrame + auxiliaryDefects).filter { it.label in defectLabels && areaRatio(it) <= 0.80f }
        return (localized + compactDefects).let { suppressOverlaps(it).take(8) }.ifEmpty { suppressOverlaps(fullFrame + auxiliaryDefects).take(8) }
    }

    private val defectLabels = setOf(
        "Возможная утечка",
        "Возможная коррозия",
        "След перегрева"
    )

    private fun areaRatioInTile(item: ModelDetection, width: Int, height: Int): Float {
        val area = (item.x2 - item.x1).coerceAtLeast(0f) * (item.y2 - item.y1).coerceAtLeast(0f)
        return area / (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
    }

    private fun detectSingle(bitmap: Bitmap, threshold: Float, activeSession: OrtSession?, activeLabels: List<String>): List<ModelDetection> {
        val current = activeSession ?: return emptyList()
        val inputName = current.inputNames.firstOrNull() ?: return emptyList()
        val size = 320
        val resizeScale = minOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val resizedWidth = (bitmap.width * resizeScale).toInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * resizeScale).toInt().coerceAtLeast(1)
        val padX = (size - resizedWidth) / 2f
        val padY = (size - resizedHeight) / 2f
        val scaled = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val inputBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(inputBitmap).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            drawBitmap(scaled, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val values = FloatArray(3 * size * size)
        var offset = 0
        for (channel in 0..2) for (y in 0 until size) for (x in 0 until size) {
            val pixel = inputBitmap.getPixel(x, y)
            values[offset++] = when (channel) { 0 -> ((pixel shr 16) and 255) / 255f; 1 -> ((pixel shr 8) and 255) / 255f; else -> (pixel and 255) / 255f }
        }
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, size.toLong(), size.toLong()))
        tensor.use { input -> current.run(mapOf(inputName to input)).use { result ->
            val output = result[0].value
            if (output !is Array<*> || output.isEmpty() || output[0] !is Array<*>) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val rows = output as Array<Array<FloatArray>>
            // Ultralytics detect exports [1, channels, candidates]; some
            // runtimes transpose this to [1, candidates, channels].
            val channelsFirst = rows[0].size <= rows[0][0].size
            val candidateCount = if (channelsFirst) rows[0][0].size else rows[0].size
            val channelCount = if (channelsFirst) rows[0].size else rows[0][0].size
            val detections = mutableListOf<ModelDetection>()
            for (candidate in 0 until candidateCount) {
                fun value(channel: Int): Float = if (channelsFirst) rows[0][channel][candidate] else rows[0][candidate][channel]
                var bestClass = 0; var bestScore = 0f
                for (channel in 4 until channelCount) if (value(channel) > bestScore) { bestScore = value(channel); bestClass = channel - 4 }
                if (bestScore < threshold || bestClass !in activeLabels.indices) continue
                val cx = value(0); val cy = value(1); val boxWidth = value(2); val boxHeight = value(3)
                val coordinateScale = if (maxOf(cx, cy, boxWidth, boxHeight) <= 2f) size.toFloat() else 1f
                val x1 = ((cx * coordinateScale - boxWidth * coordinateScale / 2f - padX) / resizeScale).coerceIn(0f, bitmap.width.toFloat())
                val y1 = ((cy * coordinateScale - boxHeight * coordinateScale / 2f - padY) / resizeScale).coerceIn(0f, bitmap.height.toFloat())
                val x2 = ((cx * coordinateScale + boxWidth * coordinateScale / 2f - padX) / resizeScale).coerceIn(0f, bitmap.width.toFloat())
                val y2 = ((cy * coordinateScale + boxHeight * coordinateScale / 2f - padY) / resizeScale).coerceIn(0f, bitmap.height.toFloat())
                if (x2 - x1 < bitmap.width * 0.04f || y2 - y1 < bitmap.height * 0.04f) continue
                detections += ModelDetection(
                    x1, y1, x2, y2,
                    activeLabels[bestClass], bestScore
                )
            }
            return suppressOverlaps(detections).take(8)
        } }
    }

    private fun suppressOverlaps(items: List<ModelDetection>): List<ModelDetection> {
        val result = mutableListOf<ModelDetection>()
        for (candidate in items.sortedByDescending { it.confidence }) {
            if (result.none { it.label == candidate.label && iou(it, candidate) > 0.4f }) result += candidate
        }
        return result
    }

    private fun iou(first: ModelDetection, second: ModelDetection): Float {
        val left = maxOf(first.x1, second.x1)
        val top = maxOf(first.y1, second.y1)
        val right = minOf(first.x2, second.x2)
        val bottom = minOf(first.y2, second.y2)
        val intersection = ((right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f))
        val firstArea = (first.x2 - first.x1).coerceAtLeast(0f) * (first.y2 - first.y1).coerceAtLeast(0f)
        val secondArea = (second.x2 - second.x1).coerceAtLeast(0f) * (second.y2 - second.y1).coerceAtLeast(0f)
        return intersection / (firstArea + secondArea - intersection).coerceAtLeast(0.0001f)
    }
}

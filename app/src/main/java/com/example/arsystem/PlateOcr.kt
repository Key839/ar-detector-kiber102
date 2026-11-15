package com.example.arsystem

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrReading(
    val rawText: String = "",
    val normalizedText: String = "",
    val confidence: Float = 0f,
    val model: String? = null,
    val identifiers: List<String> = emptyList()
)

private fun normalizePlateText(value: String): String = value.uppercase()
    .replace('О', 'O').replace('Р', 'P').replace('А', 'A').replace('В', 'B')
    .replace('С', 'C').replace('Т', 'T').replace('Х', 'X')
    .replace('—', '-').replace('–', '-')
    .replace(Regex("[^A-ZА-Я0-9/ .-]"), " ")
    .replace(Regex("\\s+"), " ").trim()

suspend fun readEquipmentPlate(bitmap: Bitmap): OcrReading = suspendCancellableCoroutine { continuation ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { result ->
            val raw = result.text.trim()
            val normalized = normalizePlateText(raw)
            val model = when {
                Regex("AMS|PCP60S12", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "ams_pcp60s12_3"
                Regex("WILO|CRONOLINE|IL[- ]?65/160", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "wilo_cronoline_il_65_160_5_5_2"
                else -> null
            }
            val identifiers = Regex("\\b(?:\\d{2,4}[-/]?[A-ZА-Я0-9]{2,8}(?:[-/][A-ZА-Я0-9]{1,6})?|\\d{6,12}(?:[-/][A-ZА-Я0-9]{1,6})?)\\b")
                .findAll(normalized).map { it.value }.distinct().toList()
            if (continuation.isActive) continuation.resume(OcrReading(raw, normalized, if (raw.isBlank()) 0f else .72f, model, identifiers))
            recognizer.close()
        }
        .addOnFailureListener {
            if (continuation.isActive) continuation.resume(OcrReading())
            recognizer.close()
        }
    continuation.invokeOnCancellation { recognizer.close() }
}

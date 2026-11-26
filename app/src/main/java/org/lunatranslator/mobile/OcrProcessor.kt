package org.lunatranslator.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

object OcrProcessor {
    private val japaneseClient by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val latinClient by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    fun recognize(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val japanese = Tasks.await(japaneseClient.process(image), 45, TimeUnit.SECONDS).text
        return japanese.ifBlank { Tasks.await(latinClient.process(image), 45, TimeUnit.SECONDS).text }
    }

    /** ML Kit work is synchronous here; callers must use a worker thread. */
    fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val japanese = Tasks.await(
            japaneseClient.process(image),
            45,
            TimeUnit.SECONDS,
        ).text
        return japanese.ifBlank {
            Tasks.await(
                latinClient.process(image),
                45,
                TimeUnit.SECONDS,
            ).text
        }
    }
}

package com.example.offlinepdftranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.app.NotificationCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream

data class TranslatedParagraph(
    val text: String,
    val isHeading: Boolean
)

data class TranslationProgressState(
    val isRunning: Boolean = false,
    val statusMessage: String = "AI 모델(.task/.bin)과 PDF 파일을 선택해 주세요.",
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val isCompleted: Boolean = false,
    val fileName: String = ""
)

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "pdf_llm_translation_channel"
        const val NOTIFICATION_ID = 1002

        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"

        private val _progressState = MutableStateFlow(TranslationProgressState())
        val progressState = _progressState.asStateFlow()

        var completedPages: List<List<TranslatedParagraph>> = emptyList()

        fun resetCompletion() {
            _progressState.value = _progressState.value.copy(isCompleted = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriString = intent?.getStringExtra(EXTRA_PDF_URI) ?: return START_NOT_STICKY
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "document.pdf"
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return START_NOT_STICKY
        val sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "English"
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Korean"

        val pdfUri = Uri.parse(uriString)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("온디바이스 AI 엔진 로딩 중...", "S24 울트라 NPU/GPU에 모델을 올리고 있습니다.", 0, 0, true)
        )

        serviceScope.launch {
            runLlmTranslationTask(pdfUri, fileName, modelPath, sourceLang, targetLang)
        }

        return START_NOT_STICKY
    }

    private suspend fun runLlmTranslationTask(
        uri: Uri,
        fileName: String,
        modelPath: String,
        sourceLang: String,
        targetLang: String
    ) {
        var llmInference: LlmInference? = null
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                updateState(
                    isRunning = false,
                    statusMessage = "❌ AI 모델 파일을 찾을 수 없습니다. 다시 선택해 주세요."
                )
                stopSelf()
                return
            }

            updateState(
                isRunning = true,
                statusMessage = "로컬 LLM 모델 메모리에 로딩 중...\n(최초 로딩에 약 5~10초 소요됩니다)",
                isCompleted = false,
                fileName = fileName
            )

            // MediaPipe LLM Inference 엔진 초기화
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(applicationContext, options)

            updateState(
                isRunning = true,
                statusMessage = "PDF 텍스트 정제 및 단락 분석 중..."
            )
            updateNotification("PDF 분석 중...", fileName, 0, 0, true)

            val rawPages = extractPagesFromPdf(applicationContext, uri)

            if (rawPages.all { it.isBlank() }) {
                updateState(
                    isRunning = false,
                    statusMessage = "❌ 텍스트를 추출할 수 없습니다. (스캔된 이미지 PDF일 수 있습니다)"
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification("번역 실패", "텍스트를 추출할 수 없는 PDF입니다.", 0, 0, false, ongoing = false)
                stopSelf()
                return
            }

            val totalPages = rawPages.size
            val resultPages = mutableListOf<List<TranslatedParagraph>>()

            for ((pageIdx, pageText) in rawPages.withIndex()) {
                val currentPageNum = pageIdx + 1
                val msg = "AI 문맥 번역 중... ($currentPageNum / $totalPages 페이지)"
                updateState(
                    isRunning = true,
                    statusMessage = msg,
                    currentPage = currentPageNum,
                    totalPages = totalPages
                )
                updateNotification(
                    title = "AI PDF 번역 진행 중 ($currentPageNum/$totalPages 페이지)",
                    content = fileName,
                    progress = currentPageNum,
                    max = totalPages,
                    indeterminate = false
                )

                val cleanedText = cleanEnglishPdfNoise(pageText)
                val parsedParagraphs = splitIntoSmartParagraphs(cleanedText)
                val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                for ((paraIdx, pair) in parsedParagraphs.withIndex()) {
                    val (rawPara, isHeading) = pair
                    updateState(
                        isRunning = true,
                        statusMessage = "AI 번역 중... ($currentPageNum/$totalPages 페이지 - 단락 ${paraIdx + 1}/${parsedParagraphs.size})",
                        currentPage = currentPageNum,
                        totalPages = totalPages
                    )

                    val translatedText = translateParagraphWithLlm(
                        llm = llmInference,
                        paragraph = rawPara,
                        sourceLang = sourceLang,
                        targetLang = targetLang
                    )
                    translatedPageParagraphs.add(
                        TranslatedParagraph(
                            text = translatedText.ifBlank { rawPara },
                            isHeading = isHeading
                        )
                    )
                }
                resultPages.add(translatedPageParagraphs)
            }

            completedPages = resultPages

            updateState(
                isRunning = false,
                statusMessage = "✅ 고품질 AI 번역 완료! 아래 버튼을 눌러 PDF를 저장하세요.",
                currentPage = totalPages,
                totalPages = totalPages,
                isCompleted = true,
                fileName = fileName
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ AI PDF 번역 완료 ($totalPages 페이지)",
                content = "앱을 열어 번역본 PDF를 저장하세요.",
                progress = totalPages,
                max = totalPages,
                indeterminate = false,
                ongoing = false
            )
            stopSelf()

        } catch (t: Throwable) {
            updateState(
                isRunning = false,
                statusMessage = "❌ AI 실행 오류: ${t.javaClass.simpleName} - ${t.localizedMessage ?: "알 수 없는 오류"}"
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification("번역 중 오류 발생", t.localizedMessage ?: "알 수 없는 오류", 0, 0, false, ongoing = false)
            stopSelf()
        } finally {
            try {
                llmInference?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun translateParagraphWithLlm(
        llm: LlmInference,
        paragraph: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val chunks = splitIntoSafeChunks(paragraph, maxChars = 400)
        val resultBuilder = StringBuilder()

        for (chunk in chunks) {
            // Qwen2.5 Instruct 전용 ChatML 템플릿 적용
            val prompt = buildString {
                append("<|im_start|>system\n")
                append("You are a professional native translator. Translate the given $sourceLang text into natural, fluent $targetLang. Output ONLY the translated text without any explanations, notes, or quotes.<|im_end|>\n")
                append("<|im_start|>user\n")
                append(chunk)
                append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }

            val rawResponse = llm.generateResponse(prompt)
            val cleanedResponse = rawResponse
                .replace("<|im_end|>", "")
                .replace("<|endoftext|>", "")
                .replace(Regex("^(Translation:|번역:|번역 결과:)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()

            if (resultBuilder.isNotEmpty()) resultBuilder.append(" ")
            resultBuilder.append(cleanedResponse)
        }

        return resultBuilder.toString().trim()
    }

    private fun splitIntoSafeChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val sentences = text.split(Regex("(?<=[.?!;][\"'”’)\\]]?)\\s+"))
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()

        for (sentence in sentences) {
            if (buffer.isNotEmpty() && buffer.length + sentence.length > maxChars) {
                chunks.add(buffer.toString().trim())
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append(" ")
            buffer.append(sentence)
        }
        if (buffer.isNotEmpty()) {
            chunks.add(buffer.toString().trim())
        }
        return chunks
    }

    private fun updateState(
        isRunning: Boolean = _progressState.value.isRunning,
        statusMessage: String = _progressState.value.statusMessage,
        currentPage: Int = _progressState.value.currentPage,
        totalPages: Int = _progressState.value.totalPages,
        isCompleted: Boolean = _progressState.value.isCompleted,
        fileName: String = _progressState.value.fileName
    ) {
        _progressState.value = TranslationProgressState(
            isRunning = isRunning,
            statusMessage = statusMessage,
            currentPage = currentPage,
            totalPages = totalPages,
            isCompleted = isCompleted,
            fileName = fileName
        )
    }

    private fun updateNotification(
        title: String,
        content: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(title, content, progress, max, indeterminate, ongoing)
        )
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (max > 0 || indeterminate) {
            builder.setProgress(max, progress, indeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI PDF 오프라인 번역 진행률",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 진행 중인 온디바이스 AI PDF 번역 상태를 표시합니다."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

fun cleanEnglishPdfNoise(rawText: String): String {
    var text = rawText
        .replace("ﬁ", "fi")
        .replace("ﬂ", "fl")
        .replace("ﬀ", "ff")
        .replace("ﬃ", "ffi")
        .replace("ﬄ", "ffl")
        .replace("•", "- ")
        .replace("■", "- ")
        .replace("▪", "- ")

    text = text.replace(Regex("([a-zA-Z])-\\s*\\n\\s*([a-zA-Z])"), "$1$2")

    val filteredLines = text.lines().filter { line ->
        val trimmed = line.trim()
        !trimmed.matches(Regex("^[-–—]?\\s*\\d+\\s*[-–—]?$"))
    }

    return filteredLines.joinToString("\n")
}

fun extractPagesFromPdf(context: Context, uri: Uri): List<String> {
    val pages = mutableListOf<String>()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        PDDocument.load(inputStream).use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphEnd = "\n\n"
            }
            for (pageNum in 1..document.numberOfPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                pages.add(stripper.getText(document))
            }
        }
    }
    return pages
}

fun splitIntoSmartParagraphs(pageText: String): List<Pair<String, Boolean>> {
    val result = mutableListOf<Pair<String, Boolean>>()
    val lines = pageText.lines()
    val currentBuffer = StringBuilder()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (currentBuffer.isNotEmpty()) {
                val para = currentBuffer.toString().trim()
                val isHeading = para.length < 50 && !para.endsWith(".") && !para.endsWith("?") && !para.endsWith("!")
                result.add(para to isHeading)
                currentBuffer.clear()
            }
        } else {
            if (currentBuffer.isNotEmpty()) currentBuffer.append(" ")
            currentBuffer.append(trimmed)

            if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
                val para = currentBuffer.toString().trim()
                if (para.length > 120) {
                    result.add(para to false)
                    currentBuffer.clear()
                }
            }
        }
    }

    if (currentBuffer.isNotEmpty()) {
        val para = currentBuffer.toString().trim()
        val isHeading = para.length < 50 && !para.endsWith(".") && !para.endsWith("?") && !para.endsWith("!")
        result.add(para to isHeading)
    }

    return result
}

fun createStructuredPdf(pages: List<List<TranslatedParagraph>>, outputStream: OutputStream) {
    val pdfDocument = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 48f
    val contentWidth = (pageWidth - margin * 2).toInt()
    val maxContentHeight = pageHeight - margin * 2 - 20f

    val footerPaint = Paint().apply {
        color = Color.GRAY
        textSize = 9f
        textAlign = Paint.Align.CENTER
    }

    for ((pageIndex, paragraphs) in pages.withIndex()) {
        val pageNumber = pageIndex + 1
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        var fontSize = 10.5f
        var layouts: List<Pair<StaticLayout, Boolean>> = emptyList()

        while (fontSize >= 7.5f) {
            var totalHeight = 0f
            val tempLayouts = mutableListOf<Pair<StaticLayout, Boolean>>()

            for (para in paragraphs) {
                val paint = TextPaint().apply {
                    color = if (para.isHeading) Color.BLACK else Color.DKGRAY
                    textSize = if (para.isHeading) fontSize + 2f else fontSize
                    isFakeBoldText = para.isHeading
                }
                val layout = StaticLayout.Builder.obtain(
                    para.text, 0, para.text.length, paint, contentWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.15f)
                    .build()

                val spacingAfter = if (para.isHeading) fontSize * 0.8f else fontSize * 1.1f
                totalHeight += layout.height + spacingAfter
                tempLayouts.add(layout to para.isHeading)
            }

            layouts = tempLayouts
            if (totalHeight <= maxContentHeight) {
                break
            }
            fontSize -= 0.5f
        }

        var currentY = margin
        for ((layout, isHeading) in layouts) {
            if (currentY + layout.height > pageHeight - margin - 15f) break

            canvas.save()
            canvas.translate(margin, currentY)
            layout.draw(canvas)
            canvas.restore()

            val spacingAfter = if (isHeading) fontSize * 0.8f else fontSize * 1.1f
            currentY += layout.height + spacingAfter
        }

        canvas.drawText("- $pageNumber / ${pages.size} -", pageWidth / 2f, pageHeight - 24f, footerPaint)
        pdfDocument.finishPage(page)
    }

    pdfDocument.writeTo(outputStream)
    pdfDocument.close()
}
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
            buildNotification("온디바이스 AI 엔진 준비 중...", "모델을 초기화하고 있습니다.", 0, 0, true)
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
                statusMessage = "1단계: PDF 텍스트 추출 및 챕터/섹션 단위로 병합 중...",
                isCompleted = false,
                fileName = fileName
            )
            updateNotification("PDF 챕터 분석 중...", fileName, 0, 0, true)

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

            // 페이지별 텍스트를 정제한 뒤, 챕터/섹션 단위로 크게 병합 (최대 1,400자씩 꽉 채움)
            val allParsedPages = rawPages.map { pageText ->
                val cleanedText = cleanEnglishPdfNoise(pageText)
                groupIntoChapterBlocks(cleanedText, maxBlockChars = 1400)
            }

            val totalPages = allParsedPages.size
            val totalBlocksAll = allParsedPages.sumOf { it.size }.coerceAtLeast(1)

            updateState(
                isRunning = true,
                statusMessage = "2단계: S24 울트라 GPU 엔진 로딩 중... (총 ${totalPages}쪽 → ${totalBlocksAll}개 대단위 블록으로 압축)"
            )
            updateNotification("AI 모델 로딩 중...", "잠시만 기다려 주세요 (약 5초)", 0, totalBlocksAll, true)

            llmInference = try {
                val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1280)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()
                LlmInference.createFromOptions(applicationContext, gpuOptions)
            } catch (gpuEx: Throwable) {
                val defaultOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1280)
                    .build()
                LlmInference.createFromOptions(applicationContext, defaultOptions)
            }

            var processedBlocksCount = 0
            val resultPages = mutableListOf<List<TranslatedParagraph>>()

            for ((pageIdx, chapterBlocks) in allParsedPages.withIndex()) {
                val currentPageNum = pageIdx + 1
                val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                if (chapterBlocks.isEmpty()) {
                    resultPages.add(emptyList())
                    continue
                }

                for ((blockIdx, pair) in chapterBlocks.withIndex()) {
                    val (rawBlock, isHeading) = pair
                    val percent = ((processedBlocksCount.toFloat() / totalBlocksAll) * 100).toInt()
                    val statusDetail = "챕터 단위 AI 통번역 중: $percent% ($currentPageNum/${totalPages}쪽 · 블록 ${blockIdx + 1}/${chapterBlocks.size})"

                    updateState(
                        isRunning = true,
                        statusMessage = statusDetail,
                        currentPage = processedBlocksCount,
                        totalPages = totalBlocksAll
                    )
                    updateNotification(
                        title = "AI 챕터 통번역 중 ($percent%)",
                        content = "$currentPageNum/${totalPages}쪽 · 블록 ${blockIdx + 1}/${chapterBlocks.size} 통번역 중...",
                        progress = processedBlocksCount,
                        max = totalBlocksAll,
                        indeterminate = false
                    )

                    val translatedText = if (!rawBlock.any { it.isLetter() }) {
                        rawBlock
                    } else {
                        translateBlockWithLlm(
                            llm = llmInference!!,
                            blockText = rawBlock,
                            sourceLang = sourceLang,
                            targetLang = targetLang
                        )
                    }

                    // 번역된 블록 내의 줄바꿈(\n\n)을 살려 깔끔한 문단들로 복원
                    val subParagraphs = translatedText.ifBlank { rawBlock }
                        .split(Regex("\\n{2,}"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    if (subParagraphs.isEmpty()) {
                        translatedPageParagraphs.add(TranslatedParagraph(rawBlock, isHeading))
                    } else {
                        subParagraphs.forEachIndexed { idx, subPara ->
                            translatedPageParagraphs.add(
                                TranslatedParagraph(
                                    text = subPara,
                                    isHeading = isHeading && idx == 0
                                )
                            )
                        }
                    }

                    processedBlocksCount++
                }
                resultPages.add(translatedPageParagraphs)
            }

            completedPages = resultPages

            updateState(
                isRunning = false,
                statusMessage = "✅ 고품질 AI 챕터 통번역 완료! (100%) 아래 버튼을 눌러 PDF를 저장하세요.",
                currentPage = totalBlocksAll,
                totalPages = totalBlocksAll,
                isCompleted = true,
                fileName = fileName
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ AI PDF 번역 완료 (100%)",
                content = "총 ${totalPages}페이지 번역 완료! 앱을 열어 PDF를 저장하세요.",
                progress = totalBlocksAll,
                max = totalBlocksAll,
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

    private fun translateBlockWithLlm(
        llm: LlmInference,
        blockText: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val prompt = "<|im_start|>user\nTranslate the following $sourceLang text into natural, fluent $targetLang. Keep paragraph breaks. Output ONLY the translation:\n$blockText<|im_end|>\n<|im_start|>assistant\n"

        val rawResponse = llm.generateResponse(prompt)
        return rawResponse
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace(Regex("^(Translation:|번역:|번역 결과:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * 페이지 내 텍스트를 챕터 제목(Heading) 기준으로 나누되,
     * 일반 본문은 모델 한계치(1,400자)까지 여러 단락을 한 덩어리로 꽉 채워 호출 횟수를 극단적으로 줄입니다.
     */
    private fun groupIntoChapterBlocks(pageText: String, maxBlockChars: Int): List<Pair<String, Boolean>> {
        val rawParagraphs = mutableListOf<Pair<String, Boolean>>()
        val lines = pageText.lines()
        val lineBuffer = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (lineBuffer.isNotEmpty()) {
                    val para = lineBuffer.toString().trim()
                    val isHeading = para.length < 60 && !para.endsWith(".") && !para.endsWith("?") && !para.endsWith("!")
                    rawParagraphs.add(para to isHeading)
                    lineBuffer.clear()
                }
            } else {
                if (lineBuffer.isNotEmpty()) lineBuffer.append(" ")
                lineBuffer.append(trimmed)
            }
        }
        if (lineBuffer.isNotEmpty()) {
            val para = lineBuffer.toString().trim()
            val isHeading = para.length < 60 && !para.endsWith(".") && !para.endsWith("?") && !para.endsWith("!")
            rawParagraphs.add(para to isHeading)
        }

        val chapterBlocks = mutableListOf<Pair<String, Boolean>>()
        val blockBuffer = StringBuilder()
        var blockStartsWithHeading = false

        for ((para, isHeading) in rawParagraphs) {
            if (isHeading && blockBuffer.isNotEmpty()) {
                chapterBlocks.add(blockBuffer.toString().trim() to blockStartsWithHeading)
                blockBuffer.clear()
                blockStartsWithHeading = false
            }

            if (blockBuffer.isNotEmpty() && blockBuffer.length + para.length > maxBlockChars) {
                chapterBlocks.add(blockBuffer.toString().trim() to blockStartsWithHeading)
                blockBuffer.clear()
                blockStartsWithHeading = false
            }

            if (blockBuffer.isEmpty()) {
                blockStartsWithHeading = isHeading
            } else {
                blockBuffer.append("\n\n")
            }

            if (para.length > maxBlockChars) {
                val sentences = para.split(Regex("(?<=[.?!][\"'”’)\\]]?)\\s+"))
                for (sentence in sentences) {
                    if (blockBuffer.isNotEmpty() && blockBuffer.length + sentence.length > maxBlockChars) {
                        chapterBlocks.add(blockBuffer.toString().trim() to blockStartsWithHeading)
                        blockBuffer.clear()
                        blockStartsWithHeading = false
                    }
                    if (blockBuffer.isNotEmpty()) blockBuffer.append(" ")
                    blockBuffer.append(sentence)
                }
            } else {
                blockBuffer.append(para)
            }
        }

        if (blockBuffer.isNotEmpty()) {
            chapterBlocks.add(blockBuffer.toString().trim() to blockStartsWithHeading)
        }

        return chapterBlocks
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

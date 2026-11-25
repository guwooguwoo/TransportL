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
import java.nio.charset.StandardCharsets

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
                statusMessage = "1단계: PDF 텍스트 추출 및 구조 분석 중...",
                isCompleted = false,
                fileName = fileName
            )
            updateNotification("PDF 구조 분석 중...", fileName, 0, 0, true)

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

            val allParsedPages = rawPages.map { pageText ->
                val cleanedText = cleanEnglishPdfNoise(pageText)
                parseSmartLayoutUnits(cleanedText, maxUnitChars = 380)
            }

            val totalPages = allParsedPages.size
            val totalUnitsAll = allParsedPages.sumOf { it.size }.coerceAtLeast(1)

            updateState(
                isRunning = true,
                statusMessage = "2단계: S24 울트라 GPU 엔진 로딩 중... (총 ${totalPages}쪽, ${totalUnitsAll}개 항목)"
            )
            updateNotification("AI 모델 로딩 중...", "잠시만 기다려 주세요 (약 5초)", 0, totalUnitsAll, true)

            llmInference = try {
                val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()
                LlmInference.createFromOptions(applicationContext, gpuOptions)
            } catch (gpuEx: Throwable) {
                val defaultOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .build()
                LlmInference.createFromOptions(applicationContext, defaultOptions)
            }

            var processedUnitsCount = 0
            val resultPages = mutableListOf<List<TranslatedParagraph>>()

            for ((pageIdx, pageUnits) in allParsedPages.withIndex()) {
                val currentPageNum = pageIdx + 1
                val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                if (pageUnits.isEmpty()) {
                    resultPages.add(emptyList())
                    continue
                }

                for ((unitIdx, pair) in pageUnits.withIndex()) {
                    val (rawUnit, isHeading) = pair
                    val percent = ((processedUnitsCount.toFloat() / totalUnitsAll) * 100).toInt()
                    val statusDetail = "정밀 AI 번역 중: $percent% ($currentPageNum/${totalPages}쪽 · 항목 ${unitIdx + 1}/${pageUnits.size})"

                    updateState(
                        isRunning = true,
                        statusMessage = statusDetail,
                        currentPage = processedUnitsCount,
                        totalPages = totalUnitsAll
                    )
                    updateNotification(
                        title = "AI PDF 번역 중 ($percent%)",
                        content = "$currentPageNum/${totalPages}쪽 · 항목 ${unitIdx + 1}/${pageUnits.size} 번역 중...",
                        progress = processedUnitsCount,
                        max = totalUnitsAll,
                        indeterminate = false
                    )

                    val translatedText = if (!rawUnit.any { it.isLetter() }) {
                        rawUnit
                    } else {
                        translateUnitWithOneShot(
                            llm = llmInference!!,
                            unitText = rawUnit,
                            sourceLang = sourceLang,
                            targetLang = targetLang
                        )
                    }

                    val finalLines = translatedText.ifBlank { rawUnit }
                        .replace("\\n", "\n")
                        .split(Regex("\\n+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    if (finalLines.isEmpty()) {
                        translatedPageParagraphs.add(TranslatedParagraph(rawUnit, isHeading))
                    } else {
                        finalLines.forEachIndexed { idx, lineText ->
                            translatedPageParagraphs.add(
                                TranslatedParagraph(
                                    text = lineText,
                                    isHeading = isHeading && idx == 0
                                )
                            )
                        }
                    }

                    processedUnitsCount++
                }
                resultPages.add(translatedPageParagraphs)
            }

            completedPages = resultPages

            updateState(
                isRunning = false,
                statusMessage = "✅ 고품질 AI 번역 완료! (100%) 아래 버튼을 눌러 PDF를 저장하세요.",
                currentPage = totalUnitsAll,
                totalPages = totalUnitsAll,
                isCompleted = true,
                fileName = fileName
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ AI PDF 번역 완료 (100%)",
                content = "총 ${totalPages}페이지 번역 완료! 앱을 열어 PDF를 저장하세요.",
                progress = totalUnitsAll,
                max = totalUnitsAll,
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

    /**
     * AI 거절 멘트("미안해요. 번역 기능은...") 및 일본어 튐 현상을 원천 차단하는 One-Shot 완성형 프롬프트
     */
    private fun translateUnitWithOneShot(
        llm: LlmInference,
        unitText: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val examplePair = when (targetLang) {
            "Korean" -> "English: The Power of Love: Lovely Lena's Visit to Korea 164\nKorean: 사랑의 힘: 사랑스러운 레나의 한국 방문 164\n\n"
            "Vietnamese" -> "English: The Power of Love: Lovely Lena's Visit to Korea 164\nVietnamese: Sức mạnh của tình yêu: Chuyến thăm Hàn Quốc của Lena đáng yêu 164\n\n"
            else -> ""
        }

        val prompt = buildString {
            append("<|im_start|>user\n")
            append(examplePair)
            append("$sourceLang: $unitText\n")
            append("$targetLang:<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }

        val rawResponse = llm.generateResponse(prompt)
        return sanitizeAndRepairLlmOutput(rawResponse, targetLang, unitText)
    }

    /**
     * 1) Qwen BPE 토크나이저의 UTF-8 한글 깨짐(Ġìłĥ 등)을 원래 한글로 역변환 복구
     * 2) AI 거절/헛소리 응답 감지 시 자동 재시도 또는 원문 보존
     */
    private fun sanitizeAndRepairLlmOutput(raw: String, targetLang: String, originalText: String): String {
        var cleaned = raw
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("^($targetLang:|Translation:|번역:|번역 결과:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // AI가 챗봇처럼 거절하거나 헛소리를 뱉은 경우 필터링
        if (cleaned.contains("번역 기능은 현재 지원되지") ||
            cleaned.contains("이미 Korean입니다") ||
            cleaned.contains("수정을 원하시면") ||
            cleaned.contains("번역 작업을 수행하지")
        ) {
            return originalText
        }

        // BPE 깨짐 바이트 복구 시도
        cleaned = repairBrokenBpeUtf8(cleaned)
            .replace("Â·", " · ")
            .replace("Â", "")
            .replace(Regex("[Ġìłĥį¶«ªİığşç]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return cleaned
    }

    /**
     * Qwen2.5 모델이 UTF-8 바이트를 GPT-2 BPE 문자(Ġ=0x20, ì=0xEC 등)로 잘못 디코딩한 시퀀스를 원래 UTF-8 한글로 복구
     */
    private fun repairBrokenBpeUtf8(input: String): String {
        return try {
            val normalized = input.replace('Ġ', ' ')
            val sb = StringBuilder()
            var i = 0
            while (i < normalized.length) {
                val c1 = normalized[i].code
                // UTF-8 한글 3바이트 시작 범위 (0xEA ~ 0xED)가 Latin-1로 잘못 디코딩된 경우
                if (c1 in 0xEA..0xED && i + 2 < normalized.length) {
                    val c2 = normalized[i + 1].code
                    val c3 = normalized[i + 2].code
                    if (c2 in 0x80..0xBF && c3 in 0x80..0xBF) {
                        val bytes = byteArrayOf(c1.toByte(), c2.toByte(), c3.toByte())
                        sb.append(String(bytes, StandardCharsets.UTF_8))
                        i += 3
                        continue
                    }
                }
                sb.append(normalized[i])
                i++
            }
            sb.toString()
        } catch (_: Exception) {
            input
        }
    }

    private fun parseSmartLayoutUnits(pageText: String, maxUnitChars: Int): List<Pair<String, Boolean>> {
        val units = mutableListOf<Pair<String, Boolean>>()
        val lines = pageText.lines()
        val bodyBuffer = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                if (bodyBuffer.isNotEmpty()) {
                    addSplitBodyUnits(units, bodyBuffer.toString().trim(), maxUnitChars)
                    bodyBuffer.clear()
                }
                continue
            }

            // 목차 줄 판별: 줄 끝에 숫자가 있거나, 점선/중간점(·)이 있는 짧은 줄
            val isTocLine = trimmed.matches(Regex(".*(\\.{2,}|\\s+|·)\\d{1,4}$"))
            val isHeadingLine = !isTocLine && trimmed.length < 60 &&
                    !trimmed.endsWith(".") && !trimmed.endsWith("?") && !trimmed.endsWith("!") && !trimmed.endsWith(",")

            if (isTocLine || isHeadingLine) {
                if (bodyBuffer.isNotEmpty()) {
                    addSplitBodyUnits(units, bodyBuffer.toString().trim(), maxUnitChars)
                    bodyBuffer.clear()
                }
                units.add(trimmed to isHeadingLine)
            } else {
                if (bodyBuffer.isNotEmpty()) bodyBuffer.append(" ")
                bodyBuffer.append(trimmed)
            }
        }

        if (bodyBuffer.isNotEmpty()) {
            addSplitBodyUnits(units, bodyBuffer.toString().trim(), maxUnitChars)
        }

        return units
    }

    private fun addSplitBodyUnits(
        targetList: MutableList<Pair<String, Boolean>>,
        paragraph: String,
        maxChars: Int
    ) {
        if (paragraph.length <= maxChars) {
            targetList.add(paragraph to false)
            return
        }

        val sentences = paragraph.split(Regex("(?<=[.?!][\"'”’)\\]]?)\\s+"))
        val chunkBuffer = StringBuilder()

        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                if (chunkBuffer.isNotEmpty()) {
                    targetList.add(chunkBuffer.toString().trim() to false)
                    chunkBuffer.clear()
                }
                var idx = 0
                while (idx < sentence.length) {
                    val end = (idx + maxChars).coerceAtMost(sentence.length)
                    targetList.add(sentence.substring(idx, end).trim() to false)
                    idx = end
                }
                continue
            }

            if (chunkBuffer.isNotEmpty() && chunkBuffer.length + sentence.length > maxChars) {
                targetList.add(chunkBuffer.toString().trim() to false)
                chunkBuffer.clear()
            }
            if (chunkBuffer.isNotEmpty()) chunkBuffer.append(" ")
            chunkBuffer.append(sentence)
        }

        if (chunkBuffer.isNotEmpty()) {
            targetList.add(chunkBuffer.toString().trim() to false)
        }
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
        .replace("·", " · ")

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
                    textSize = if (para.isHeading) fontSize + 1.5f else fontSize
                    isFakeBoldText = para.isHeading
                }
                val layout = StaticLayout.Builder.obtain(
                    para.text, 0, para.text.length, paint, contentWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.15f)
                    .build()

                val spacingAfter = if (para.isHeading) fontSize * 0.6f else fontSize * 0.9f
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

            val spacingAfter = if (isHeading) fontSize * 0.6f else fontSize * 0.9f
            currentY += layout.height + spacingAfter
        }

        canvas.drawText("- $pageNumber / ${pages.size} -", pageWidth / 2f, pageHeight - 24f, footerPaint)
        pdfDocument.finishPage(page)
    }

    pdfDocument.writeTo(outputStream)
    pdfDocument.close()
}

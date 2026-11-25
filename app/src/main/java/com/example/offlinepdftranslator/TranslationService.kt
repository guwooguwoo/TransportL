package com.example.offlinepdftranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    val fileName: String = "",
    val savedFilePath: String = ""
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
            _progressState.value = _progressState.value.copy(
                isCompleted = false,
                savedFilePath = ""
            )
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
                fileName = fileName,
                savedFilePath = ""
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

            // 번역 완료 즉시 내부 저장소 Download(다운로드) 폴더에 워드(.docx) 파일로 자동 저장
            val baseName = fileName.substringBeforeLast(".")
            val outputDocxName = "${baseName}_${targetLang}_AI번역본.docx"
            val savedLocation = saveDocxToDownloads(applicationContext, outputDocxName, resultPages)

            updateState(
                isRunning = false,
                statusMessage = "✅ 워드(.docx) 번역 완료 및 자동 저장됨!\n저장 위치: $savedLocation",
                currentPage = totalUnitsAll,
                totalPages = totalUnitsAll,
                isCompleted = true,
                fileName = fileName,
                savedFilePath = savedLocation
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ 워드(.docx) 번역 및 저장 완료 (100%)",
                content = "다운로드 폴더에 '$outputDocxName' 저장 완료!",
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
     * 한국어, 베트남어, 러시아어 등 언어별 맞춤형 One-Shot 프롬프트
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
            "Russian" -> "English: The Power of Love: Lovely Lena's Visit to Korea 164\nRussian: Сила любви: Визит милой Лены в Корею 164\n\n"
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

    private fun sanitizeAndRepairLlmOutput(raw: String, targetLang: String, originalText: String): String {
        var cleaned = raw
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("^($targetLang:|Translation:|번역:|번역 결과:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleaned.contains("번역 기능은 현재 지원되지") ||
            cleaned.contains("이미 Korean입니다") ||
            cleaned.contains("수정을 원하시면") ||
            cleaned.contains("번역 작업을 수행하지")
        ) {
            return originalText
        }

        cleaned = repairBrokenBpeUtf8(cleaned)
            .replace("Â·", " · ")
            .replace("Â", "")
            .replace(Regex("[Ġìłĥį¶«ªİığşç]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return cleaned
    }

    private fun repairBrokenBpeUtf8(input: String): String {
        return try {
            val normalized = input.replace('Ġ', ' ')
            val sb = StringBuilder()
            var i = 0
            while (i < normalized.length) {
                val c1 = normalized[i].code
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
        fileName: String = _progressState.value.fileName,
        savedFilePath: String = _progressState.value.savedFilePath
    ) {
        _progressState.value = TranslationProgressState(
            isRunning = isRunning,
            statusMessage = statusMessage,
            currentPage = currentPage,
            totalPages = totalPages,
            isCompleted = isCompleted,
            fileName = fileName,
            savedFilePath = savedFilePath
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

/**
 * 스마트폰 내부 저장소의 공용 Download(다운로드) 폴더에 워드(.docx) 파일로 직접 저장
 */
fun saveDocxToDownloads(
    context: Context,
    fileName: String,
    pages: List<List<TranslatedParagraph>>
): String {
    val docxMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, docxMimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("다운로드 폴더에 파일을 생성할 수 없습니다.")

        resolver.openOutputStream(itemUri)?.use { outputStream ->
            writeStructuredDocx(pages, outputStream)
        }

        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(itemUri, contentValues, null, null)

        "내장 메모리/Download/$fileName"
    } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val outFile = File(downloadsDir, fileName)
        FileOutputStream(outFile).use { outputStream ->
            writeStructuredDocx(pages, outputStream)
        }
        outFile.absolutePath
    }
}

/**
 * 외부 라이브러리 없이 표준 OpenXML(.docx) 구조로 워드 문서를 직접 생성 (제목 굵게, 본문 줄간격, 페이지 나누기 지원)
 */
fun writeStructuredDocx(pages: List<List<TranslatedParagraph>>, outputStream: OutputStream) {
    ZipOutputStream(outputStream).use { zip ->
        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()
        zip.write(contentTypes.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()
        zip.write(rels.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. word/document.xml (실제 번역된 본문 및 서식)
        zip.putNextEntry(ZipEntry("word/document.xml"))
        val docXml = StringBuilder()
        docXml.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        docXml.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        docXml.append("""<w:body>""")

        for ((pageIdx, paragraphs) in pages.withIndex()) {
            for (para in paragraphs) {
                val escapedText = escapeXmlText(para.text)
                docXml.append("<w:p>")
                docXml.append("<w:pPr><w:spacing w:after=\"${if (para.isHeading) "160" else "120"}\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>")
                docXml.append("<w:r>")
                if (para.isHeading) {
                    docXml.append("<w:rPr><w:b/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr>")
                } else {
                    docXml.append("<w:rPr><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr>")
                }
                docXml.append("<w:t xml:space=\"preserve\">$escapedText</w:t>")
                docXml.append("</w:r>")
                docXml.append("</w:p>")
            }

            // 페이지가 여러 장일 경우 각 원본 페이지 끝에 페이지 나누기(Page Break) 삽입
            if (pageIdx < pages.size - 1) {
                docXml.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }

        docXml.append("</w:body></w:document>")
        zip.write(docXml.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }
}

private fun escapeXmlText(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> {
                // XML 1.0 허용 제어문자 필터링
                if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) {
                    sb.append(ch)
                }
            }
        }
    }
    return sb.toString()
}

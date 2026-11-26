package com.example.offlinepdftranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranslatedParagraph(
    val text: String,
    val isHeading: Boolean
)

data class TranslationProgressState(
    val isRunning: Boolean = false,
    val statusMessage: String = "PDF 파일을 선택해 주세요.",
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
        const val CHANNEL_ID = "pdf_mlkit_translation_channel"
        const val NOTIFICATION_ID = 1002

        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"

        private val _progressState = MutableStateFlow(TranslationProgressState())
        val progressState = _progressState.asStateFlow()

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
        val sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "English"
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Russian"

        val pdfUri = Uri.parse(uriString)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("초고속 PDF 번역 준비 중...", "언어 엔진을 준비하고 있습니다.", 0, 0, true)
        )

        serviceScope.launch {
            runPureMlKitTranslationTask(pdfUri, fileName, sourceLang, targetLang)
        }

        return START_NOT_STICKY
    }

    private suspend fun runPureMlKitTranslationTask(
        uri: Uri,
        fileName: String,
        sourceLang: String,
        targetLang: String
    ) {
        var mlKitTranslator: Translator? = null

        try {
            // =========================================================================
            // 1단계: 고해상도 PDF 렌더링 + 상하단 머리말/꼬리말(러닝헤드) 제외 본문 정밀 추출
            // =========================================================================
            updateState(
                isRunning = true,
                statusMessage = "1단계: PDF 페이지 고해상도 스캔 및 본문 추출 중...",
                isCompleted = false,
                fileName = fileName,
                savedFilePath = ""
            )
            updateNotification("1단계: PDF 텍스트 추출 중...", fileName, 0, 0, true)

            val rawPages = extractCleanBodyPagesFromPdf(applicationContext, uri) { current, total ->
                updateState(
                    isRunning = true,
                    statusMessage = "1단계: PDF 고해상도 텍스트 추출 중... ($current / $total 페이지)",
                    currentPage = current,
                    totalPages = total
                )
                updateNotification(
                    title = "1단계: PDF 텍스트 추출 ($current/${total}쪽)",
                    content = fileName,
                    progress = current,
                    max = total,
                    indeterminate = false
                )
            }

            if (rawPages.all { it.isBlank() }) {
                updateState(
                    isRunning = false,
                    statusMessage = "❌ PDF에서 텍스트를 인식할 수 없습니다."
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification("번역 실패", "텍스트를 추출할 수 없는 PDF입니다.", 0, 0, false, ongoing = false)
                stopSelf()
                return
            }

            val allParsedPages = rawPages.map { pageText ->
                val cleanedText = cleanEnglishPdfNoise(pageText)
                parseSmartLayoutUnits(cleanedText)
            }

            val totalPages = allParsedPages.size
            val totalUnitsAll = allParsedPages.sumOf { it.size }.coerceAtLeast(1)

            // =========================================================================
            // 2단계: Google ML Kit 오프라인 초고속 정밀 번역 (지명/내용 왜곡 없음)
            // =========================================================================
            updateState(
                isRunning = true,
                statusMessage = "2단계: 오프라인 번역 언어팩 준비 중... (최초 1회만 다운로드)"
            )
            updateNotification("2단계: 번역 언어팩 확인 중...", "잠시만 기다려 주세요.", 0, totalUnitsAll, true)

            val mlKitSrc = mapToMlKitLanguage(sourceLang)
            val mlKitTgt = mapToMlKitLanguage(targetLang)
            val translatorOptions = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSrc)
                .setTargetLanguage(mlKitTgt)
                .build()
            mlKitTranslator = Translation.getClient(translatorOptions)

            val conditions = DownloadConditions.Builder().build()
            mlKitTranslator.downloadModelIfNeeded(conditions).awaitTask()

            val finalResultPages = mutableListOf<List<TranslatedParagraph>>()
            var processedCount = 0

            for ((pageIdx, pageUnits) in allParsedPages.withIndex()) {
                val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                for ((rawUnit, isHeading) in pageUnits) {
                    processedCount++
                    if (processedCount % 5 == 0 || processedCount == totalUnitsAll) {
                        val percent = ((processedCount.toFloat() / totalUnitsAll) * 100).toInt()
                        updateState(
                            isRunning = true,
                            statusMessage = "2단계: 초고속 정밀 번역 중... $percent% (${pageIdx + 1}/${totalPages}쪽)",
                            currentPage = processedCount,
                            totalPages = totalUnitsAll
                        )
                        updateNotification(
                            title = "초고속 정밀 번역 중 ($percent%)",
                            content = "${pageIdx + 1}/${totalPages}쪽 번역 진행 중...",
                            progress = processedCount,
                            max = totalUnitsAll,
                            indeterminate = false
                        )
                    }

                    val translatedText = if (!rawUnit.any { it.isLetter() }) {
                        rawUnit
                    } else {
                        try {
                            mlKitTranslator.translate(rawUnit).awaitTask().ifBlank { rawUnit }
                        } catch (_: Exception) {
                            rawUnit
                        }
                    }

                    translatedPageParagraphs.add(
                        TranslatedParagraph(
                            text = translatedText.trim(),
                            isHeading = isHeading
                        )
                    )
                }
                finalResultPages.add(translatedPageParagraphs)
            }

            mlKitTranslator.close()
            mlKitTranslator = null

            // =========================================================================
            // 3단계: 내장 메모리 Download 폴더에 워드(.docx) 파일 자동 저장
            // =========================================================================
            val baseName = fileName.substringBeforeLast(".")
            val outputDocxName = "${baseName}_${targetLang}_번역본.docx"
            val savedLocation = saveDocxToDownloads(applicationContext, outputDocxName, finalResultPages)

            updateState(
                isRunning = false,
                statusMessage = "✅ 초고속 번역 완료 및 워드(.docx) 자동 저장됨!\n저장 위치: $savedLocation",
                currentPage = totalUnitsAll,
                totalPages = totalUnitsAll,
                isCompleted = true,
                fileName = fileName,
                savedFilePath = savedLocation
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ 워드(.docx) 번역 저장 완료 (100%)",
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
                statusMessage = "❌ 실행 오류: ${t.javaClass.simpleName} - ${t.localizedMessage ?: "알 수 없는 오류"}"
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification("번역 중 오류 발생", t.localizedMessage ?: "알 수 없는 오류", 0, 0, false, ongoing = false)
            stopSelf()
        } finally {
            try {
                mlKitTranslator?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 페이지 상단 6% 및 하단 7% 영역(책 제목 러닝헤드 및 페이지 번호)을 자동으로 걸러내고
     * 초고해상도(2.5배) 렌더링으로 철자 오인식(OCR 오타)을 최소화하여 추출합니다.
     */
    private suspend fun extractCleanBodyPagesFromPdf(
        context: Context,
        uri: Uri,
        onPageProgress: (current: Int, total: Int) -> Unit
    ): List<String> {
        val pages = mutableListOf<String>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageCount = renderer.pageCount
                    for (i in 0 until pageCount) {
                        onPageProgress(i + 1, pageCount)
                        renderer.openPage(i).use { page ->
                            val width = (page.width * 2.5f).toInt().coerceAtMost(2800)
                            val height = (page.height * 2.5f).toInt().coerceAtMost(3800)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                val image = InputImage.fromBitmap(bitmap, 0)
                                val visionText = recognizer.process(image).awaitTask()

                                // 머리말(상단 6%)과 꼬리말/페이지번호(하단 7%) 좌표 필터링
                                val topMarginLimit = height * 0.06f
                                val bottomMarginLimit = height * 0.93f

                                val sortedBlocks = visionText.textBlocks
                                    .filter { block ->
                                        val box = block.boundingBox
                                        if (box == null) true
                                        else box.bottom > topMarginLimit && box.top < bottomMarginLimit
                                    }
                                    .sortedBy { it.boundingBox?.top ?: 0 }

                                val pageBuilder = StringBuilder()
                                for (block in sortedBlocks) {
                                    if (pageBuilder.isNotEmpty()) pageBuilder.append("\n\n")
                                    pageBuilder.append(block.text)
                                }
                                pages.add(pageBuilder.toString())
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
        }
        return pages
    }

    private fun mapToMlKitLanguage(langName: String): String {
        return when (langName) {
            "Korean" -> TranslateLanguage.KOREAN
            "English" -> TranslateLanguage.ENGLISH
            "Russian" -> TranslateLanguage.RUSSIAN
            "Vietnamese" -> TranslateLanguage.VIETNAMESE
            "Japanese" -> TranslateLanguage.JAPANESE
            "Chinese" -> TranslateLanguage.CHINESE
            "Spanish" -> TranslateLanguage.SPANISH
            "French" -> TranslateLanguage.FRENCH
            "German" -> TranslateLanguage.GERMAN
            "Thai" -> TranslateLanguage.THAI
            else -> TranslateLanguage.ENGLISH
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { exception -> cont.resumeWithException(exception) }
    }

    private fun parseSmartLayoutUnits(pageText: String): List<Pair<String, Boolean>> {
        val units = mutableListOf<Pair<String, Boolean>>()
        val blocks = pageText.split(Regex("\\n{2,}"))

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val isTocBlock = lines.all { it.matches(Regex(".*(\\.{2,}|\\s+|·)\\d{1,4}$")) }
            if (isTocBlock) {
                for (line in lines) {
                    units.add(line to false)
                }
                continue
            }

            val joinedBlock = lines.joinToString(" ")
            val isHeading = joinedBlock.length < 60 &&
                    !joinedBlock.endsWith(".") && !joinedBlock.endsWith("?") && !joinedBlock.endsWith("!") && !joinedBlock.endsWith(",")

            units.add(joinedBlock to isHeading)
        }

        return units
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
                "오프라인 PDF 번역 진행률",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 진행 중인 오프라인 PDF 번역 상태를 표시합니다."
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

fun writeStructuredDocx(pages: List<List<TranslatedParagraph>>, outputStream: OutputStream) {
    ZipOutputStream(outputStream).use { zip ->
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

        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val rels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()
        zip.write(rels.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

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
                if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) {
                    sb.append(ch)
                }
            }
        }
    }
    return sb.toString()
}

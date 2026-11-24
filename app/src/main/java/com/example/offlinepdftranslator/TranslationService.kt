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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.io.OutputStream

data class TranslatedParagraph(
    val text: String,
    val isHeading: Boolean
)

data class TranslationProgressState(
    val isRunning: Boolean = false,
    val statusMessage: String = "PDF 파일을 가져온 뒤 언어를 선택해 주세요.",
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val isCompleted: Boolean = false,
    val fileName: String = ""
)

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "pdf_translation_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"

        private val _progressState = MutableStateFlow(TranslationProgressState())
        val progressState = _progressState.asStateFlow()

        // 번역 완료된 데이터 보관 (UI에서 파일 저장 시 사용)
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
        val sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: TranslateLanguage.ENGLISH
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: TranslateLanguage.KOREAN

        val pdfUri = Uri.parse(uriString)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("번역 준비 중...", "언어팩 및 문서를 확인하고 있습니다.", 0, 0, true)
        )

        serviceScope.launch {
            runTranslationTask(pdfUri, fileName, sourceLang, targetLang)
        }

        return START_NOT_STICKY
    }

    private suspend fun runTranslationTask(
        uri: Uri,
        fileName: String,
        sourceLang: String,
        targetLang: String
    ) {
        try {
            updateState(
                isRunning = true,
                statusMessage = "오프라인 언어팩 확인 중...\n(최초 1회만 다운로드되며 이후 백그라운드 오프라인 작동)",
                isCompleted = false,
                fileName = fileName
            )

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            val translator = Translation.getClient(options)

            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()

            updateState(
                isRunning = true,
                statusMessage = "PDF 텍스트 정제 및 문장 분석 중..."
            )
            updateNotification("PDF 분석 중...", fileName, 0, 0, true)

            val rawPages = extractPagesFromPdf(applicationContext, uri)

            if (rawPages.all { it.isBlank() }) {
                updateState(
                    isRunning = false,
                    statusMessage = "❌ 텍스트를 추출할 수 없습니다. (스캔된 이미지 PDF일 수 있습니다)"
                )
                updateNotification("번역 실패", "텍스트를 추출할 수 없는 PDF입니다.", 0, 0, false)
                stopSelf()
                return
            }

            val totalPages = rawPages.size
            val resultPages = mutableListOf<List<TranslatedParagraph>>()

            for ((pageIdx, pageText) in rawPages.withIndex()) {
                val currentPageNum = pageIdx + 1
                val msg = "백그라운드 번역 중... ($currentPageNum / $totalPages 페이지)"
                updateState(
                    isRunning = true,
                    statusMessage = msg,
                    currentPage = currentPageNum,
                    totalPages = totalPages
                )
                updateNotification(
                    title = "PDF 번역 진행 중 ($currentPageNum/$totalPages 페이지)",
                    content = fileName,
                    progress = currentPageNum,
                    max = totalPages,
                    indeterminate = false
                )

                val cleanedText = cleanEnglishPdfNoise(pageText)
                val parsedParagraphs = splitIntoSmartParagraphs(cleanedText)
                val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                for ((rawPara, isHeading) in parsedParagraphs) {
                    val translatedText = translateByCompleteSentences(
                        translator = translator,
                        paragraph = rawPara,
                        isTargetKorean = (targetLang == TranslateLanguage.KOREAN)
                    )
                    translatedPageParagraphs.add(
                        TranslatedParagraph(
                            text = translatedText,
                            isHeading = isHeading
                        )
                    )
                }
                resultPages.add(translatedPageParagraphs)
            }

            translator.close()
            completedPages = resultPages

            updateState(
                isRunning = false,
                statusMessage = "✅ 번역 완료! 아래 버튼을 눌러 PDF를 저장하세요.",
                currentPage = totalPages,
                totalPages = totalPages,
                isCompleted = true,
                fileName = fileName
            )

            // 완료 알림 띄우기 (포그라운드 종료 후에도 알림바에 완료 표시 유지)
            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ PDF 번역 완료 ($totalPages 페이지)",
                content = "앱을 열어 번역본 PDF를 저장하세요.",
                progress = totalPages,
                max = totalPages,
                indeterminate = false,
                ongoing = false
            )
            stopSelf()

        } catch (e: Exception) {
            updateState(
                isRunning = false,
                statusMessage = "❌ 오류 발생: ${e.localizedMessage}\n(최초 실행 시 언어팩 다운로드를 위해 인터넷 연결이 필요합니다)"
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification("번역 중 오류 발생", e.localizedMessage ?: "알 수 없는 오류", 0, 0, false, ongoing = false)
            stopSelf()
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
                "PDF 오프라인 번역 진행률",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 진행 중인 PDF 번역 상태를 표시합니다."
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

// ============================================================================
// PDF 추출, 영어 전처리, 한글 번역투 교정 후처리 및 PDF 생성 유틸리티
// ============================================================================

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

    // 줄 끝 하이픈으로 끊긴 영단어 복원
    text = text.replace(Regex("([a-zA-Z])-\\s*\\n\\s*([a-zA-Z])"), "$1$2")

    // 단독 숫자(페이지 번호) 줄 제거
    val filteredLines = text.lines().filter { line ->
        val trimmed = line.trim()
        !trimmed.matches(Regex("^[-–—]?\\s*\\d+\\s*[-–—]?$"))
    }

    return filteredLines.joinToString("\n")
}

/**
 * 완전한 문장(. ? ! ;) 단위로 분리 번역 후, 한국어일 경우 번역투 교정 후처리 적용
 */
suspend fun translateByCompleteSentences(
    translator: Translator,
    paragraph: String,
    isTargetKorean: Boolean
): String {
    // 영어 약어 뒤의 마침표 보호
    val protectedText = paragraph.replace(
        Regex("\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc|e\\.g|i\\.e)\\.", RegexOption.IGNORE_CASE)
    ) { matchResult ->
        matchResult.value.replace(".", "###DOT###")
    }

    // . ? ! 및 긴 복문을 나누는 세미콜론(;) 기준으로 완전한 절/문장 분리
    val rawSentences = protectedText.split(Regex("(?<=[.?!;][\"'”’)\\]]?)\\s+"))
        .map { it.replace("###DOT###", ".").trim() }
        .filter { it.isNotEmpty() }

    if (rawSentences.isEmpty()) {
        val raw = translator.translate(paragraph).await()
        return if (isTargetKorean) polishKoreanTranslation(raw) else raw
    }

    val translatedSentences = mutableListOf<String>()
    for (sentence in rawSentences) {
        val translated = translator.translate(sentence).await().trim()
        val polished = if (isTargetKorean) polishKoreanTranslation(translated) else translated
        translatedSentences.add(polished)
    }

    return translatedSentences.joinToString(" ")
}

/**
 * [1번 개선안 핵심] 영한 기계번역 특유의 어색한 번역투·피동형·대명사를 자연스러운 한국어로 교정
 */
fun polishKoreanTranslation(text: String): String {
    var result = text

    // 1. 어색한 이중 피동 및 영어식 수동태 표현 교정
    result = result
        .replace("되어진다", "된다")
        .replace("되어집니다", "됩니다")
        .replace("되어졌다", "되었다")
        .replace("되어지는", "되는")
        .replace("되어질", "될")
        .replace("보여진다", "보인다")
        .replace("보여집니다", "보입니다")
        .replace("쓰여진다", "쓰인다")
        .replace("불리어지는", "불리는")
        .replace("만들어졌다", "만들어졌다")
        .replace("에 의해 수행된다", "을 통해 이루어진다")
        .replace("에 의해 야기된다", "때문에 발생한다")

    // 2. 문장 맨 앞의 불필요한 지시대명사 직역(It is / They are / This is) 완화
    result = result.replace(Regex("^(그것은|그들은|그것들은)\\s+"), "")

    // 3. 어색한 연결어 및 직역투 순화
    result = result
        .replace("당신은 ", "") // 불특정 다수를 가리키는 'You' 직역 제거
        .replace("당신의 ", "")
        .replace("할 수 있는 능력을 가지고 있다", "할 수 있다")
        .replace("하는 것이 가능하다", "할 수 있다")
        .replace("에 대한 이해를 제공한다", "을 이해하도록 돕는다")
        .replace("중요한 역할을 한다", "중요한 역할을 합니다".let { "중요한 역할을 한다" })

    // 4. 문장 부호 및 띄어쓰기 정리
    result = result
        .replace(Regex("\\s+([.,?!])"), "$1") // 마침표/쉼표 앞 불필요한 공백 제거
        .replace(Regex("([.,?!])([가-힣a-zA-Z0-9])"), "$1 $2") // 문장부호 뒤 띄어쓰기 보정
        .replace(Regex("\\s{2,}"), " ") // 이중 공백 제거

    return result.trim()
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
                if (para.length > 100) {
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

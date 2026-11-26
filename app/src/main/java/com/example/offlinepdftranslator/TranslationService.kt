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
import com.google.android.gms.tasks.Task
import com.google.mediapipe.tasks.genai.llminference.LlmInference
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
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Russian"

        val pdfUri = Uri.parse(uriString)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("하이브리드 AI 번역 준비 중...", "초벌 엔진과 AI 윤문 엔진을 준비합니다.", 0, 0, true)
        )

        serviceScope.launch {
            runHybridTranslationTask(pdfUri, fileName, modelPath, sourceLang, targetLang)
        }

        return START_NOT_STICKY
    }

    private suspend fun runHybridTranslationTask(
        uri: Uri,
        fileName: String,
        modelPath: String,
        sourceLang: String,
        targetLang: String
    ) {
        var mlKitTranslator: Translator? = null
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

            // =========================================================================
            // 0단계: PDF 텍스트 추출 및 구조 분석
            // =========================================================================
            updateState(
                isRunning = true,
                statusMessage = "0단계: PDF 텍스트 추출 및 단락 구조 분석 중...",
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
                parseSmartLayoutUnits(cleanedText, maxUnitChars = 420)
            }

            val totalPages = allParsedPages.size
            val totalUnitsAll = allParsedPages.sumOf { it.size }.coerceAtLeast(1)

            // =========================================================================
            // 1단계: Google ML Kit 초고속 초벌 직역 (단어 누락 방지 및 전체 직역본 확보)
            // =========================================================================
            updateState(
                isRunning = true,
                statusMessage = "1단계: 초벌 번역 엔진 준비 중... (오프라인 언어팩 확인)"
            )
            updateNotification("1단계: 초벌 번역 준비 중...", "언어팩을 확인하고 있습니다.", 0, totalUnitsAll, true)

            val mlKitSrc = mapToMlKitLanguage(sourceLang)
            val mlKitTgt = mapToMlKitLanguage(targetLang)
            val translatorOptions = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSrc)
                .setTargetLanguage(mlKitTgt)
                .build()
            mlKitTranslator = Translation.getClient(translatorOptions)

            // 기기에 언어팩이 없을 경우 최초 1회 자동 다운로드
            val conditions = DownloadConditions.Builder().build()
            mlKitTranslator.downloadModelIfNeeded(conditions).awaitTask()

            val draftPages = mutableListOf<List<Pair<String, Boolean>>>()
            var draftCount = 0

            for ((pageIdx, pageUnits) in allParsedPages.withIndex()) {
                val draftPageUnits = mutableListOf<Pair<String, Boolean>>()
                for ((rawUnit, isHeading) in pageUnits) {
                    draftCount++
                    if (draftCount % 10 == 0 || draftCount == totalUnitsAll) {
                        val draftPercent = ((draftCount.toFloat() / totalUnitsAll) * 100).toInt()
                        updateState(
                            isRunning = true,
                            statusMessage = "1단계: 초벌 직역본 생성 중... $draftPercent% (${pageIdx + 1}/${totalPages}쪽)"
                        )
                        updateNotification(
                            title = "1단계: 초벌 직역 중 ($draftPercent%)",
                            content = "${pageIdx + 1}/${totalPages}쪽 초벌 직역 진행 중...",
                            progress = draftCount,
                            max = totalUnitsAll,
                            indeterminate = false
                        )
                    }

                    val draftText = if (!rawUnit.any { it.isLetter() }) {
                        rawUnit
                    } else {
                        try {
                            mlKitTranslator.translate(rawUnit).awaitTask().ifBlank { rawUnit }
                        } catch (_: Exception) {
                            rawUnit
                        }
                    }
                    draftPageUnits.add(draftText to isHeading)
                }
                draftPages.add(draftPageUnits)
            }

            // 1단계 완료 후 ML Kit 메모리 즉시 해제
            mlKitTranslator.close()
            mlKitTranslator = null

            // =========================================================================
            // 2단계: Qwen2.5 온디바이스 AI로 초벌 번역문의 어순 및 문맥 자연스럽게 다듬기
            // =========================================================================
            updateState(
                isRunning = true,
                statusMessage = "2단계: AI 문맥·어순 윤문 엔진 로딩 중... (총 ${totalPages}쪽, ${totalUnitsAll}개 항목)"
            )
            updateNotification("2단계: AI 윤문 엔진 로딩 중...", "초벌 번역본의 문맥을 다듬을 준비 중입니다.", 0, totalUnitsAll, true)

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

            var polishedUnitsCount = 0
            val finalResultPages = mutableListOf<List<TranslatedParagraph>>()

            for ((pageIdx, draftPageUnits) in draftPages.withIndex()) {
                val currentPageNum = pageIdx + 1
                val polishedPageParagraphs = mutableListOf<TranslatedParagraph>()

                if (draftPageUnits.isEmpty()) {
                    finalResultPages.add(emptyList())
                    continue
                }

                for ((unitIdx, pair) in draftPageUnits.withIndex()) {
                    val (draftText, isHeading) = pair
                    val percent = ((polishedUnitsCount.toFloat() / totalUnitsAll) * 100).toInt()
                    val statusDetail = "2단계 AI 문맥·어순 윤문 중: $percent% ($currentPageNum/${totalPages}쪽 · 항목 ${unitIdx + 1}/${draftPageUnits.size})"

                    updateState(
                        isRunning = true,
                        statusMessage = statusDetail,
                        currentPage = polishedUnitsCount,
                        totalPages = totalUnitsAll
                    )
                    updateNotification(
                        title = "2단계: AI 문맥·어순 다듬기 ($percent%)",
                        content = "$currentPageNum/${totalPages}쪽 · 항목 ${unitIdx + 1}/${draftPageUnits.size} 교정 중...",
                        progress = polishedUnitsCount,
                        max = totalUnitsAll,
                        indeterminate = false
                    )

                    // 4단어 이하의 아주 짧은 제목이나 숫자는 초벌 번역문 그대로 유지 (속도 향상 및 왜곡 방지)
                    val wordCount = draftText.trim().split(Regex("\\s+")).size
                    val polishedText = if (!draftText.any { it.isLetter() } || wordCount <= 3) {
                        draftText
                    } else {
                        polishDraftWithLlm(
                            llm = llmInference!!,
                            draftText = draftText,
                            targetLang = targetLang
                        )
                    }

                    val finalLines = polishedText.ifBlank { draftText }
                        .replace("\\n", "\n")
                        .split(Regex("\\n+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    if (finalLines.isEmpty()) {
                        polishedPageParagraphs.add(TranslatedParagraph(draftText, isHeading))
                    } else {
                        finalLines.forEachIndexed { idx, lineText ->
                            polishedPageParagraphs.add(
                                TranslatedParagraph(
                                    text = lineText,
                                    isHeading = isHeading && idx == 0
                                )
                            )
                        }
                    }

                    polishedUnitsCount++
                }
                finalResultPages.add(polishedPageParagraphs)
            }

            completedPages = finalResultPages

            // =========================================================================
            // 3단계: 내장 메모리 Download 폴더에 워드(.docx) 파일 자동 저장
            // =========================================================================
            val baseName = fileName.substringBeforeLast(".")
            val outputDocxName = "${baseName}_${targetLang}_AI윤문번역본.docx"
            val savedLocation = saveDocxToDownloads(applicationContext, outputDocxName, finalResultPages)

            updateState(
                isRunning = false,
                statusMessage = "✅ 초벌 직역 + AI 문맥 윤문 완료 및 워드 자동 저장됨!\n저장 위치: $savedLocation",
                currentPage = totalUnitsAll,
                totalPages = totalUnitsAll,
                isCompleted = true,
                fileName = fileName,
                savedFilePath = savedLocation
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            updateNotification(
                title = "✅ 워드(.docx) 윤문 번역 저장 완료 (100%)",
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
            try {
                llmInference?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 2단계 전용: 초벌 직역문(draftText)의 어색한 직역투와 어순만 자연스러운 모국어 문맥으로 다듬는 프롬프트
     * - 원문을 다시 해석하지 않고 초벌 번역문만 교정하므로 속도가 빠르고 누락이 없습니다.
     * - AI가 교정에 실패하거나 이상한 문장을 뱉으면 안전하게 1단계 초벌 번역문(draftText)으로 자동 복구합니다.
     */
    private fun polishDraftWithLlm(
        llm: LlmInference,
        draftText: String,
        targetLang: String
    ): String {
        val examplePair = when (targetLang) {
            "Korean" -> "Draft: 그는 결정했다 떠나기로 그의 집을, 왜냐하면 비가 오고 있었기 때문에.\nNatural: 비가 오고 있었기 때문에 그는 집을 떠나기로 결정했다.\n\n"
            "Russian" -> "Draft: Он решил покинуть его дом, потому что дождь шел.\nNatural: Он решил уйти из дома, так как шёл дождь.\n\n"
            "Vietnamese" -> "Draft: Anh ấy đã quyết định rời khỏi ngôi nhà của anh ấy bởi vì trời đang mưa.\nNatural: Vì trời đang mưa nên anh ấy quyết định rời khỏi nhà.\n\n"
            else -> ""
        }

        val prompt = buildString {
            append("<|im_start|>user\n")
            append("Rewrite the following $targetLang draft so that the word order and phrasing sound completely natural, keeping the exact meaning and numbers. Output ONLY the rewritten text.\n\n")
            append(examplePair)
            append("Draft: $draftText\n")
            append("Natural:<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }

        val rawResponse = llm.generateResponse(prompt)
        return sanitizeAndVerifyPolishedOutput(rawResponse, draftText)
    }

    private fun sanitizeAndVerifyPolishedOutput(raw: String, fallbackDraft: String): String {
        var cleaned = raw
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("^(Natural:|Draft:|Rewritten:|교정:|수정:)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // AI 거절 멘트 감지 시 초벌 번역본 유지
        if (cleaned.contains("번역 기능은") ||
            cleaned.contains("지원되지 않") ||
            cleaned.contains("수정을 원하시면") ||
            cleaned.isBlank()
        ) {
            return fallbackDraft
        }

        cleaned = repairBrokenBpeUtf8(cleaned)
            .replace("Â·", " · ")
            .replace("Â", "")
            .replace(Regex("[Ġìłĥį¶«ªİığşç]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // 길이 검증: 윤문 결과가 초벌 번역본보다 비정상적으로 짧아졌거나(내용 누락) 2배 이상 길어졌으면(헛소리 추가) 초벌본으로 안전 복구
        if (cleaned.length < fallbackDraft.length * 0.45 || cleaned.length > fallbackDraft.length * 2.0) {
            return fallbackDraft
        }

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

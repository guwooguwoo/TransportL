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
    val savedFileName: String = ""
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

        fun resetCompletion() {
            _progressState.value = _progressState.value.copy(isCompleted = false, savedFileName = "")
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
                savedFileName = ""
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

            // 번역 완료 즉시 내부 저장소 Download 폴더에 Word(.doc) 파일로 자동 저장
            val baseName = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
            val outputFileName = "${baseName}_${targetLang}_번역본.doc"

            updateState(
                isRunning = true,
                statusMessage = "3단계: 다운로드(Download) 폴더에 워드 파일 저장 중...",
                currentPage = totalUnitsAll,
                totalPages = totalUnitsAll
            )

            val savedSuccess = saveWordDocumentToDownloads(
                context = applicationContext,
                outputFileName = outputFileName,
                documentTitle = baseName,
                pages = resultPages
            )

            if (savedSuccess) {
                updateState(
                    isRunning = false,
                    statusMessage = "✅ 번역 및 워드 자동 저장 완료!\n저장 위치: 내 파일 > 다운로드(Download) > $outputFileName",
                    currentPage = totalUnitsAll,
                    totalPages = totalUnitsAll,
                    isCompleted = true,
                    savedFileName = outputFileName
                )

                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification(
                    title = "✅ 워드(.doc) 자동 저장 완료!",
                    content = "Download 폴더에 '$outputFileName' 저장됨",
                    progress = totalUnitsAll,
                    max = totalUnitsAll,
                    indeterminate = false,
                    ongoing = false
                )
            } else {
                updateState(
                    isRunning = false,
                    statusMessage = "❌ 다운로드 폴더 저장 중 권한 또는 입출력 오류가 발생했습니다."
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification("저장 오류 발생", "다운로드 폴더에 파일을 쓰지 못했습니다.", 0, 0, false, ongoing = false)
            }

            stopSelf()

        } catch (t: Throwable) {
            updateState(
                isRunning = false,
                statusMessage = "❌ AI 실행 오류: ${t.javaClass.simpleName} -${t.localizedMessage ?: "알 수 없는 오류"}"
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
     * 한국어, 베트남어, 러시아어 등 언어별 One-Shot 예시를 포함해
     * 챗봇 거절 멘트("미안해요...") 없이 순수 번역 결과만 출력하게 유도
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
            append("$sourceLang:$unitText\n")
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
        val lines =

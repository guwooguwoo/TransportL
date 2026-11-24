package com.example.offlinepdftranslator

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OfflinePdfTranslatorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflinePdfTranslatorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val languages = remember {
        listOf(
            "한국어" to TranslateLanguage.KOREAN,
            "영어" to TranslateLanguage.ENGLISH,
            "일본어" to TranslateLanguage.JAPANESE,
            "중국어" to TranslateLanguage.CHINESE,
            "스페인어" to TranslateLanguage.SPANISH,
            "프랑스어" to TranslateLanguage.FRENCH,
            "독일어" to TranslateLanguage.GERMAN,
            "러시아어" to TranslateLanguage.RUSSIAN,
            "베트남어" to TranslateLanguage.VIETNAMESE,
            "태국어" to TranslateLanguage.THAI
        )
    }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("선택된 파일 없음") }
    var sourceLang by remember { mutableStateOf(languages[1]) } // 기본값: 영어
    var targetLang by remember { mutableStateOf(languages[0]) } // 기본값: 한국어
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("PDF 파일을 가져온 뒤 언어를 선택해 주세요.") }
    var translatedResultText by remember { mutableStateOf("") }

    // 1. PDF 파일 가져오기 런처
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedPdfUri = uri
            var name = "selected.pdf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
            selectedFileName = name
            statusMessage = "파일 준비 완료. 번역본 내보내기를 누르세요."
        }
    }

    // 2. 번역본 PDF로 내보내기 런처
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { saveUri: Uri? ->
        if (saveUri != null && translatedResultText.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                        createFormattedPdf(
                            title = selectedFileName.removeSuffix(".pdf") + " (번역본)",
                            content = translatedResultText,
                            outputStream = outputStream
                        )
                    }
                    withContext(Dispatchers.Main) {
                        statusMessage = "✅ PDF 번역본 내보내기 완료!"
                        Toast.makeText(context, "PDF 파일이 성공적으로 저장되었습니다.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "❌ 저장 실패: ${e.message}"
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "오프라인 PDF 번역기",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // [버튼 1] PDF 파일 가져오기
        Button(
            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isProcessing
        ) {
            Text(text = "1. PDF 파일 가져오기", fontSize = 16.sp)
        }

        Text(
            text = "선택된 파일: $selectedFileName",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
        )

        // [언어 선택] 번역할 언어 (원본)
        LanguageDropdown(
            label = "번역할 언어 (원본)",
            languages = languages,
            selectedLanguage = sourceLang,
            enabled = !isProcessing,
            onLanguageSelected = { sourceLang = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // [언어 선택] 번역될 언어 (결과)
        LanguageDropdown(
            label = "번역될 언어 (결과)",
            languages = languages,
            selectedLanguage = targetLang,
            enabled = !isProcessing,
            onLanguageSelected = { targetLang = it }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // [버튼 2] 번역 실행 및 PDF로 내보내기
        Button(
            onClick = {
                val uri = selectedPdfUri
                if (uri == null) {
                    Toast.makeText(context, "먼저 PDF 파일을 가져와 주세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (sourceLang.second == targetLang.second) {
                    Toast.makeText(context, "원본 언어와 번역될 언어가 같습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                coroutineScope.launch {
                    isProcessing = true
                    try {
                        statusMessage = "오프라인 언어팩 확인 중...\n(최초 1회만 다운로드되며 이후 오프라인 작동)"
                        val options = TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLang.second)
                            .setTargetLanguage(targetLang.second)
                            .build()
                        val translator = Translation.getClient(options)

                        val conditions = DownloadConditions.Builder().build()
                        translator.downloadModelIfNeeded(conditions).await()

                        statusMessage = "PDF에서 텍스트 추출 중..."
                        val extractedText = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                PDDocument.load(inputStream).use { document ->
                                    PDFTextStripper().getText(document)
                                }
                            } ?: ""
                        }

                        if (extractedText.isBlank()) {
                            statusMessage = "❌ 텍스트를 추출할 수 없습니다. (스캔된 이미지 PDF일 수 있습니다)"
                            isProcessing = false
                            return@launch
                        }

                        statusMessage = "오프라인 번역 진행 중..."
                        val paragraphs = extractedText.split("\n\n").filter { it.isNotBlank() }
                        val translatedBuilder = StringBuilder()

                        for ((index, paragraph) in paragraphs.withIndex()) {
                            statusMessage = "오프라인 번역 중... (${index + 1} / ${paragraphs.size} 문단)"
                            val translatedLine = translator.translate(paragraph.trim()).await()
                            translatedBuilder.append(translatedLine).append("\n\n")
                        }

                        translator.close()
                        translatedResultText = translatedBuilder.toString()

                        statusMessage = "번역 완료! PDF 저장 위치를 선택하세요."
                        val exportFileName = selectedFileName.removeSuffix(".pdf") + "_번역본.pdf"
                        exportLauncher.launch(exportFileName)

                    } catch (e: Exception) {
                        statusMessage = "❌ 오류 발생: ${e.localizedMessage}\n(최초 실행 시 언어팩 다운로드를 위해 인터넷 연결이 필요합니다)"
                    } finally {
                        isProcessing = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isProcessing && selectedPdfUri != null
        ) {
            Text(text = "2. PDF 번역본 내보내기", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isProcessing) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 12.dp))
        }

        Text(
            text = statusMessage,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 번역된 텍스트를 깔끔한 A4 규격의 서식 있는 PDF 문서로 생성하는 함수
 */
fun createFormattedPdf(title: String, content: String, outputStream: OutputStream) {
    val pdfDocument = PdfDocument()
    // A4 표준 포인트 규격 (595 x 842)
    val pageWidth = 595
    val pageHeight = 842
    val margin = 50f
    val contentWidth = (pageWidth - margin * 2).toInt()

    val titlePaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 16f
        isFakeBoldText = true
    }

    val bodyPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 11f
    }

    val footerPaint = Paint().apply {
        color = Color.GRAY
        textSize = 9f
        textAlign = Paint.Align.CENTER
    }

    // 전체 본문 레이아웃 계산 (자동 줄바꿈 처리)
    val staticLayout = StaticLayout.Builder.obtain(
        content, 0, content.length, bodyPaint, contentWidth
    )
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(4f, 1.2f)
        .build()

    var currentLine = 0
    var pageNumber = 1

    while (currentLine < staticLayout.lineCount) {
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        var currentY = margin

        // 첫 페이지 상단에는 문서 제목과 구분선 추가
        if (pageNumber == 1) {
            canvas.drawText(title, margin, currentY + 14f, titlePaint)
            currentY += 32f
            canvas.drawLine(margin, currentY, pageWidth - margin, currentY, footerPaint)
            currentY += 20f
        }

        val maxContentY = pageHeight - margin - 20f

        // 현재 페이지에 들어갈 수 있는 만큼 줄 단위로 그리기
        while (currentLine < staticLayout.lineCount) {
            val lineHeight = (staticLayout.getLineBottom(currentLine) - staticLayout.getLineTop(currentLine)).toFloat()
            if (currentY + lineHeight > maxContentY) {
                break // 다음 페이지로 넘김
            }

            val lineStart = staticLayout.getLineStart(currentLine)
            val lineEnd = staticLayout.getLineEnd(currentLine)
            val lineText = content.substring(lineStart, lineEnd)

            canvas.drawText(lineText, margin, currentY + lineHeight - 4f, bodyPaint)
            currentY += lineHeight
            currentLine++
        }

        // 하단 페이지 번호 표시
        canvas.drawText("- $pageNumber -", pageWidth / 2f, pageHeight - 25f, footerPaint)

        pdfDocument.finishPage(page)
        pageNumber++
    }

    pdfDocument.writeTo(outputStream)
    pdfDocument.close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(
    label: String,
    languages: List<Pair<String, String>>,
    selectedLanguage: Pair<String, String>,
    enabled: Boolean,
    onLanguageSelected: (Pair<String, String>) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLanguage.first,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.first) },
                    onClick = {
                        onLanguageSelected(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}

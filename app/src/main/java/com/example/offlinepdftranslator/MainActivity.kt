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

// 단락 정보를 담는 데이터 클래스
data class TranslatedParagraph(
    val text: String,
    val isHeading: Boolean
)

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
    
    // 페이지별 > 단락별 번역 결과를 저장하는 리스트
    var translatedPages by remember { mutableStateOf<List<List<TranslatedParagraph>>>(emptyList()) }

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
        if (saveUri != null && translatedPages.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                        createStructuredPdf(
                            pages = translatedPages,
                            outputStream = outputStream
                        )
                    }
                    withContext(Dispatchers.Main) {
                        statusMessage = "✅ 단락 및 페이지 맞춤 PDF 내보내기 완료!"
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

                        statusMessage = "PDF 페이지 및 단락 분석 중..."
                        val rawPages = withContext(Dispatchers.IO) {
                            extractPagesFromPdf(context, uri)
                        }

                        if (rawPages.all { it.isBlank() }) {
                            statusMessage = "❌ 텍스트를 추출할 수 없습니다. (스캔된 이미지 PDF일 수 있습니다)"
                            isProcessing = false
                            return@launch
                        }

                        val resultPages = mutableListOf<List<TranslatedParagraph>>()

                        for ((pageIdx, pageText) in rawPages.withIndex()) {
                            statusMessage = "오프라인 번역 중... (${pageIdx + 1} / ${rawPages.size} 페이지)"
                            val parsedParagraphs = splitIntoSmartParagraphs(pageText)
                            val translatedPageParagraphs = mutableListOf<TranslatedParagraph>()

                            for ((rawPara, isHeading) in parsedParagraphs) {
                                val translatedText = translator.translate(rawPara).await()
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
                        translatedPages = resultPages

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
 * PDF를 페이지 단위로 텍스트 추출
 */
fun extractPagesFromPdf(context: android.content.Context, uri: Uri): List<String> {
    val pages = mutableListOf<String>()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        PDDocument.load(inputStream).use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true // 시각적 배치 순서대로 정렬
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
 * PDF 특유의 강제 줄바꿈을 복원하고 제목/본문 단락을 구분하는 함수
 */
fun splitIntoSmartParagraphs(pageText: String): List<Pair<String, Boolean>> {
    val result = mutableListOf<Pair<String, Boolean>>()
    val lines = pageText.lines()
    val currentBuffer = StringBuilder()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (currentBuffer.isNotEmpty()) {
                val para = currentBuffer.toString().trim()
                val isHeading = para.length < 45 && !para.endsWith(".") && !para.endsWith("。")
                result.add(para to isHeading)
                currentBuffer.clear()
            }
        } else {
            // 하이픈으로 단어가 잘린 경우 연결
            if (currentBuffer.endsWith("-")) {
                currentBuffer.deleteCharAt(currentBuffer.length - 1)
                currentBuffer.append(trimmed)
            } else {
                if (currentBuffer.isNotEmpty()) currentBuffer.append(" ")
                currentBuffer.append(trimmed)
            }

            // 마침표 등으로 문장이 명확히 끝나고 다음 줄이 새 단락일 가능성이 높을 때 분리
            if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") || trimmed.endsWith("。")) {
                val para = currentBuffer.toString().trim()
                if (para.length > 80) { // 충분히 긴 문단이면 단락 확정
                    result.add(para to false)
                    currentBuffer.clear()
                }
            }
        }
    }

    if (currentBuffer.isNotEmpty()) {
        val para = currentBuffer.toString().trim()
        val isHeading = para.length < 45 && !para.endsWith(".") && !para.endsWith("。")
        result.add(para to isHeading)
    }

    return result
}

/**
 * 원본 페이지 수와 단락 간격을 유지하여 A4 규격 PDF로 생성하는 함수
 */
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

        // 한 페이지에 모든 단락이 알맞게 들어가도록 폰트 크기 자동 조절 (10.5pt ~ 7.5pt)
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
                break // 현재 페이지 안에 깔끔하게 들어감
            }
            fontSize -= 0.5f
        }

        // 계산된 레이아웃을 캔버스에 그리기
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

        // 하단 원본 페이지 번호 표시
        canvas.drawText("- $pageNumber / ${pages.size} -", pageWidth / 2f, pageHeight - 24f, footerPaint)
        pdfDocument.finishPage(page)
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

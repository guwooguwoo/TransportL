package com.example.offlinepdftranslator

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PdfBox 초기화 (필수)
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

    // 지원하는 주요 다중 언어 목록 (ML Kit 오프라인 모델)
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

    // 2. 번역본 내보내기(저장) 런처
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { saveUri: Uri? ->
        if (saveUri != null && translatedResultText.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                        outputStream.write(translatedResultText.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        statusMessage = "✅ 번역본 내보내기 완료!"
                        Toast.makeText(context, "파일이 성공적으로 저장되었습니다.", Toast.LENGTH_LONG).show()
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

        // [버튼 2] 번역 실행 및 번역본 내보내기
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
                        // 1단계: 오프라인 언어 모델 확인 및 다운로드 (최초 1회만 인터넷 필요)
                        statusMessage = "오프라인 언어팩 확인 중...\n(최초 1회만 다운로드되며 이후 오프라인 작동)"
                        val options = TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLang.second)
                            .setTargetLanguage(targetLang.second)
                            .build()
                        val translator = Translation.getClient(options)

                        val conditions = DownloadConditions.Builder().build()
                        translator.downloadModelIfNeeded(conditions).await()

                        // 2단계: PDF에서 텍스트 추출
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

                        // 3단계: 문단 단위 오프라인 번역 진행
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

                        // 4단계: 저장 위치 선택 창 띄우기
                        statusMessage = "번역 완료! 저장할 위치를 선택하세요."
                        val exportFileName = selectedFileName.removeSuffix(".pdf") + "_번역본.txt"
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
            Text(text = "2. 번역본 내보내기", fontSize = 16.sp)
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

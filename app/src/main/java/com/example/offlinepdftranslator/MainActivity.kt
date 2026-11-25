package com.example.offlinepdftranslator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    val progressState by TranslationService.progressState.collectAsState()

    // 생성형 AI(LLM)는 프롬프트 기반이므로 거의 모든 주요 언어를 자유롭게 지원합니다
    val languages = remember {
        listOf(
            "한국어" to "Korean",
            "영어" to "English",
            "일본어" to "Japanese",
            "중국어" to "Chinese",
            "스페인어" to "Spanish",
            "프랑스어" to "French",
            "독일어" to "German",
            "러시아어" to "Russian",
            "베트남어" to "Vietnamese",
            "태국어" to "Thai"
        )
    }

    // 앱 내부 저장소에 이미 복사된 AI 모델이 있는지 확인
    var savedModelPath by remember {
        val existingModel = File(context.filesDir, "llm_model.task")
        mutableStateOf(if (existingModel.exists() && existingModel.length() > 10_000_000L) existingModel.absolutePath else null)
    }
    var modelStatusText by remember {
        mutableStateOf(
            if (savedModelPath != null) "✅ AI 모델 준비됨 (내장 저장소에 등록 완료)"
            else "선택된 AI 모델 없음 (.task / .bin 파일 필요)"
        )
    }
    var isCopyingModel by remember { mutableStateOf(false) }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("선택된 파일 없음") }
    var sourceLang by remember { mutableStateOf(languages[1]) } // 기본값: 영어
    var targetLang by remember { mutableStateOf(languages[0]) } // 기본값: 한국어
    var localStatus by remember { mutableStateOf("AI 모델 등록 후 PDF 파일을 가져와 주세요.") }

    // Android 13/14+ 알림 권한 요청 런처
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "알림 권한을 허용해야 상단바 진행률을 볼 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // [런처 0] 온디바이스 AI 모델 파일(.task / .bin) 등록 런처 (최초 1회만 등록하면 영구 보존)
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri)
            coroutineScope.launch {
                isCopyingModel = true
                modelStatusText = "⏳ AI 모델을 앱 내부로 등록 중... (용량에 따라 10~20초 소요)"
                val copiedPath = withContext(Dispatchers.IO) {
                    copyModelToInternalStorage(context, uri)
                }
                isCopyingModel = false
                if (copiedPath != null) {
                    savedModelPath = copiedPath
                    modelStatusText = "✅ AI 모델 등록 완료: $fileName"
                    Toast.makeText(context, "AI 모델 등록이 완료되었습니다!", Toast.LENGTH_SHORT).show()
                } else {
                    modelStatusText = "❌ 모델 등록 실패 (저장공간 부족 또는 잘못된 파일)"
                }
            }
        }
    }

    // [런처 1] PDF 파일 가져오기 런처
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedPdfUri = uri
            selectedFileName = getFileNameFromUri(context, uri)
            TranslationService.resetCompletion()
            localStatus = "파일 준비 완료. 백그라운드 번역을 시작할 수 있습니다."
        }
    }

    // [런처 2] 번역 완료된 PDF 저장 런처
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { saveUri: Uri? ->
        val pagesToSave = TranslationService.completedPages
        if (saveUri != null && pagesToSave.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                        createStructuredPdf(
                            pages = pagesToSave,
                            outputStream = outputStream
                        )
                    }
                    withContext(Dispatchers.Main) {
                        localStatus = "✅ 고품질 AI PDF 번역본 저장 완료!"
                        TranslationService.resetCompletion()
                        Toast.makeText(context, "PDF 파일이 성공적으로 저장되었습니다.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        localStatus = "❌ 저장 실패: ${e.message}"
                    }
                }
            }
        }
    }

    LaunchedEffect(progressState.isCompleted) {
        if (progressState.isCompleted && TranslationService.completedPages.isNotEmpty()) {
            val baseName = progressState.fileName.ifEmpty { selectedFileName }.removeSuffix(".pdf")
            exportLauncher.launch("${baseName}_AI번역본.pdf")
        }
    }

    val isProcessing = progressState.isRunning || isCopyingModel
    val displayStatus = if (progressState.isRunning || progressState.isCompleted) {
        progressState.statusMessage
    } else {
        localStatus
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "S24 울트라 AI PDF 번역기",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // [버튼 0] 온디바이스 AI 모델 등록 (최초 1회)
        OutlinedButton(
            onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !isProcessing
        ) {
            Text(
                text = if (savedModelPath != null) "AI 모델 변경하기 (.task / .bin)" else "0. AI 모델 등록하기 (최초 1회)",
                fontSize = 14.sp
            )
        }

        Text(
            text = modelStatusText,
            fontSize = 12.sp,
            color = if (savedModelPath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
        )

        // [버튼 1] PDF 파일 가져오기
        Button(
            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isProcessing
        ) {
            Text(text = "1. PDF 파일 가져오기", fontSize = 16.sp)
        }

        Text(
            text = "선택된 파일: $selectedFileName",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
        )

        // [언어 선택] 번역할 언어 (원본)
        LanguageDropdown(
            label = "번역할 언어 (원본)",
            languages = languages,
            selectedLanguage = sourceLang,
            enabled = !isProcessing,
            onLanguageSelected = { sourceLang = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // [언어 선택] 번역될 언어 (결과)
        LanguageDropdown(
            label = "번역될 언어 (결과)",
            languages = languages,
            selectedLanguage = targetLang,
            enabled = !isProcessing,
            onLanguageSelected = { targetLang = it }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // [버튼 2] 백그라운드 AI 번역 및 PDF 내보내기
        Button(
            onClick = {
                if (progressState.isCompleted && TranslationService.completedPages.isNotEmpty()) {
                    val baseName = progressState.fileName.ifEmpty { selectedFileName }.removeSuffix(".pdf")
                    exportLauncher.launch("${baseName}_AI번역본.pdf")
                    return@Button
                }

                val modelPath = savedModelPath
                if (modelPath == null) {
                    Toast.makeText(context, "먼저 상단에서 AI 모델(.task/.bin)을 등록해 주세요.", Toast.LENGTH_LONG).show()
                    return@Button
                }

                val uri = selectedPdfUri
                if (uri == null) {
                    Toast.makeText(context, "먼저 PDF 파일을 가져와 주세요.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (sourceLang.second == targetLang.second) {
                    Toast.makeText(context, "원본 언어와 번역될 언어가 같습니다.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val serviceIntent = Intent(context, TranslationService::class.java).apply {
                    putExtra(TranslationService.EXTRA_PDF_URI, uri.toString())
                    putExtra(TranslationService.EXTRA_FILE_NAME, selectedFileName)
                    putExtra(TranslationService.EXTRA_MODEL_PATH, modelPath)
                    putExtra(TranslationService.EXTRA_SOURCE_LANG, sourceLang.second)
                    putExtra(TranslationService.EXTRA_TARGET_LANG, targetLang.second)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Toast.makeText(context, "백그라운드에서 AI 번역을 시작합니다. 상단 알림바를 확인하세요.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isProcessing && ((savedModelPath != null && selectedPdfUri != null) || progressState.isCompleted)
        ) {
            Text(
                text = if (progressState.isCompleted) "2. 번역본 PDF 저장하기" else "2. 백그라운드 AI 번역 시작",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isProcessing) {
            if (progressState.totalPages > 0) {
                val progressRatio = progressState.currentPage.toFloat() / progressState.totalPages.toFloat()
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .padding(bottom = 8.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .padding(bottom = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = displayStatus,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = "selected_file"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

fun copyModelToInternalStorage(context: Context, uri: Uri): String? {
    return try {
        val destFile = File(context.filesDir, "llm_model.task")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 8192 * 4)
            }
        }
        if (destFile.exists() && destFile.length() > 10_000_000L) destFile.absolutePath else null
    } catch (e: Exception) {
        null
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

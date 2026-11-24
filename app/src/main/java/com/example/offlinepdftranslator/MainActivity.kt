package com.example.offlinepdftranslator

import android.Manifest
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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 백그라운드 서비스의 진행 상태를 실시간으로 구독
    val progressState by TranslationService.progressState.collectAsState()

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
    var localStatus by remember { mutableStateOf("PDF 파일을 가져온 뒤 언어를 선택해 주세요.") }

    // Android 13/14+ 알림 권한 요청 런처
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "알림 권한이 거부되어 상단바 진행률이 표시되지 않을 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    // 앱 시작 시 알림 권한 확인 (갤럭시 S24 울트라 등 최신 기기 필수)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
            TranslationService.resetCompletion()
            localStatus = "파일 준비 완료. '번역 시작'을 누르면 백그라운드에서 진행됩니다."
        }
    }

    // 2. 번역 완료된 PDF 저장 런처
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
                        localStatus = "✅ PDF 번역본 저장 완료!"
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

    // 번역이 완료되면 자동으로 저장 다이얼로그 띄우기
    LaunchedEffect(progressState.isCompleted) {
        if (progressState.isCompleted && TranslationService.completedPages.isNotEmpty()) {
            val baseName = progressState.fileName.ifEmpty { selectedFileName }.removeSuffix(".pdf")
            exportLauncher.launch("${baseName}_번역본.pdf")
        }
    }

    val isProcessing = progressState.isRunning
    val displayStatus = if (isProcessing || progressState.isCompleted) {
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

        // [버튼 2] 백그라운드 번역 시작 / 완료 시 PDF 저장하기
        Button(
            onClick = {
                if (progressState.isCompleted && TranslationService.completedPages.isNotEmpty()) {
                    val baseName = progressState.fileName.ifEmpty { selectedFileName }.removeSuffix(".pdf")
                    exportLauncher.launch("${baseName}_번역본.pdf")
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

                // 백그라운드 서비스 시작
                val serviceIntent = Intent(context, TranslationService::class.java).apply {
                    putExtra(TranslationService.EXTRA_PDF_URI, uri.toString())
                    putExtra(TranslationService.EXTRA_FILE_NAME, selectedFileName)
                    putExtra(TranslationService.EXTRA_SOURCE_LANG, sourceLang.second)
                    putExtra(TranslationService.EXTRA_TARGET_LANG, targetLang.second)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Toast.makeText(context, "백그라운드에서 번역을 시작합니다. 상단 알림바에서 진행률을 확인하세요.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isProcessing && (selectedPdfUri != null || progressState.isCompleted)
        ) {
            Text(
                text = if (progressState.isCompleted) "2. 번역본 PDF 저장하기" else "2. 백그라운드 번역 및 내보내기",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 진행률 프로그레스 바 표시
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
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
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

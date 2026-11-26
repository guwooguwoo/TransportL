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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    val progressState by TranslationService.progressState.collectAsState()

    val languages = remember {
        listOf(
            "한국어" to "Korean",
            "영어" to "English",
            "러시아어" to "Russian",
            "베트남어" to "Vietnamese",
            "일본어" to "Japanese",
            "중국어" to "Chinese",
            "스페인어" to "Spanish",
            "프랑스어" to "French",
            "독일어" to "German",
            "태국어" to "Thai"
        )
    }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("선택된 파일 없음") }
    var sourceLang by remember { mutableStateOf(languages[1]) } // 기본값: 영어
    var targetLang by remember { mutableStateOf(languages[2]) } // 기본값: 러시아어
    var localStatus by remember { mutableStateOf("별도 AI 모델 파일 없이 초고속으로 번역되어 [Download] 폴더에 워드(.docx)로 자동 저장됩니다.") }

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

    // PDF 파일 가져오기 런처
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedPdfUri = uri
            selectedFileName = getFileNameFromUri(context, uri)
            TranslationService.resetCompletion()
            localStatus = "파일 준비 완료! 아래 버튼을 누르면 초고속 번역 후 Download 폴더에 워드(.docx)로 저장됩니다."
        }
    }

    val isProcessing = progressState.isRunning
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
            text = "초고속 오프라인 PDF 번역기",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Google ML Kit 오프라인 정밀 번역 엔진",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 28.dp)
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
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
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

        Spacer(modifier = Modifier.height(28.dp))

        // [버튼 2] 초고속 번역 시작
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

                TranslationService.resetCompletion()

                val serviceIntent = Intent(context, TranslationService::class.java).apply {
                    putExtra(TranslationService.EXTRA_PDF_URI, uri.toString())
                    putExtra(TranslationService.EXTRA_FILE_NAME, selectedFileName)
                    putExtra(TranslationService.EXTRA_SOURCE_LANG, sourceLang.second)
                    putExtra(TranslationService.EXTRA_TARGET_LANG, targetLang.second)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Toast.makeText(context, "번역을 시작합니다. 완료되면 Download 폴더에 워드(.docx)로 자동 저장됩니다!", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            enabled = !isProcessing && selectedPdfUri != null
        ) {
            Text(
                text = "2. 초고속 번역 시작 (워드 자동 저장)",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

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
            color = if (progressState.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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

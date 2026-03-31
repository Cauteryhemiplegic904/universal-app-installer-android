package github.amurcanov.installer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("installer_prefs", Context.MODE_PRIVATE)

    var isRussian: Boolean
        get() = prefs.getBoolean("isRussian", true)
        set(value) { prefs.edit().putBoolean("isRussian", value).apply() }
}

class MainViewModel : ViewModel() {
    private var prefs: AppPrefs? = null
    
    private val _isRussian = MutableStateFlow(true)
    val isRussian: StateFlow<Boolean> = _isRussian.asStateFlow()
    
    val currentStatus = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val installProgress = MutableStateFlow(0f)
    val loadedAppIcon = MutableStateFlow<Bitmap?>(null)
    
    fun init(context: Context) {
        if (prefs == null) {
            prefs = AppPrefs(context.applicationContext)
            _isRussian.value = prefs?.isRussian ?: true
        }
    }
    
    fun toggleLanguage() {
        _isRussian.value = !_isRussian.value
        prefs?.isRussian = _isRussian.value
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init(this)
        val uriToHandle = intent?.data

        setContent {
            // Using a strictly static Purple Dark Color Scheme as requested
            val colorScheme = darkColorScheme(
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                surfaceVariant = Color(0xFF2A2A2A),
                primary = Color(0xFF622FAC),
                secondary = Color(0xFF03DAC5),
                onBackground = Color.White,
                onSurface = Color.White
            )
            
            MaterialTheme(colorScheme = colorScheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = this.window
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                        window.statusBarColor = android.graphics.Color.BLACK
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                    }
                }
                
                Surface(color = MaterialTheme.colorScheme.background) {
                    InstallerScreen(
                        context = this,
                        viewModel = viewModel,
                        initialUri = uriToHandle,
                        requestPermissions = { checkAndRequestPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        var hasPermissions = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                hasPermissions = false
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                hasPermissions = false
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                hasPermissions = false
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                hasPermissions = false
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
        return hasPermissions
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerScreen(
    context: Context, 
    viewModel: MainViewModel,
    initialUri: Uri?, 
    requestPermissions: () -> Boolean
) {
    val isRussian by viewModel.isRussian.collectAsState()
    val status by viewModel.currentStatus.collectAsState()
    val progress by viewModel.installProgress.collectAsState()
    val appIcon by viewModel.loadedAppIcon.collectAsState()
    
    var showFilePicker by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        InstallState.events.collect { newStatus ->
            viewModel.currentStatus.value = newStatus
        }
    }

    fun processUri(uri: Uri) {
        showFilePicker = false
        viewModel.loadedAppIcon.value = null
        viewModel.installProgress.value = 0f
        
        viewModel.viewModelScope.launch {
            handleFileUri(context, uri, viewModel)
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            processUri(initialUri)
        }
    }

    if (showFilePicker) {
        CustomFilePicker(
            isRussian = isRussian,
            onClose = { showFilePicker = false },
            onFilePicked = { file -> processUri(Uri.fromFile(file)) }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top toggles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { viewModel.toggleLanguage() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = "Change Language",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Main content
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (status !is InstallStatus.Idle) {
                // Header Icon
                if (appIcon != null && status !is InstallStatus.Error && status !is InstallStatus.Cancelled) {
                    // No background container, just a soft clip.
                    // If icon is round, clip won't cut anything. If square, lightly rounds it.
                    Image(
                        bitmap = appIcon!!.asImageBitmap(), 
                        contentDescription = "App Icon", 
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(
                                color = if (status is InstallStatus.Error) MaterialTheme.colorScheme.errorContainer 
                                        else if (status is InstallStatus.Success) MaterialTheme.colorScheme.primaryContainer 
                                        else if (status is InstallStatus.Cancelled) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(36.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (status is InstallStatus.Error) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                        } else if (status is InstallStatus.Success) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                        } else if (status is InstallStatus.Cancelled) {
                            Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                        } else {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(56.dp))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Text representation
                val statusString = when (val s = status) {
                    is InstallStatus.Preparing -> if (isRussian) "Подготовка ${s.name}..." else "Preparing ${s.name}..."
                    is InstallStatus.Extracting -> if (isRussian) "Извлечение... (${s.count} APK)" else "Extracting... (${s.count} APKs)"
                    is InstallStatus.Installing -> if (isRussian) "Установка в фоне..." else "Installing in background..."
                    is InstallStatus.Success -> if (isRussian) "Успешно установлено!" else "Successfully installed!"
                    is InstallStatus.Cancelled -> if (isRussian) "Отменено" else "Cancelled"
                    is InstallStatus.Error -> translateError(s.message, isRussian)
                    else -> ""
                }
                
                Text(
                    text = statusString,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    color = if (status is InstallStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                
                if (status is InstallStatus.Preparing || status is InstallStatus.Extracting || status is InstallStatus.Installing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val animatedProgress by animateFloatAsState(targetValue = progress)
                    if (progress > 0 && progress < 1f) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else if (progress == 0f) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = {
                    val hasPerms = requestPermissions()
                    if (hasPerms) {
                        showFilePicker = true
                        if (status is InstallStatus.Error) viewModel.currentStatus.value = InstallStatus.Idle
                    } else {
                        // Android system dialogs/settings are shown to the user.
                        // We reset to Idle so the UI doesn't look like an error occurred while they grant rights.
                        viewModel.currentStatus.value = InstallStatus.Idle
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onBackground
                )
            ) {
                Icon(Icons.Default.Assignment, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isRussian) "Выбрать файл" else "Select File",
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFilePicker(isRussian: Boolean, onClose: () -> Unit, onFilePicked: (File) -> Unit) {
    val root = Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(root) }
    
    var files by remember { mutableStateOf(emptyList<File>()) }
    
    LaunchedEffect(currentDir) {
        withContext(Dispatchers.IO) {
            val allList = currentDir.listFiles()?.toList() ?: emptyList()
            files = allList.filter { 
                if (it.isHidden) false
                else if (it.isDirectory) true
                else {
                    val name = it.name.lowercase()
                    name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm")
                }
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    
    BackHandler {
        if (currentDir.absolutePath == root.absolutePath) {
            onClose()
        } else {
            val parent = currentDir.parentFile
            if (parent != null) currentDir = parent
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (currentDir.absolutePath == root.absolutePath) {
                            if (isRussian) "Внутренняя память" else "Internal Storage"
                        } else {
                            currentDir.name
                        },
                        fontSize = 20.sp,
                        maxLines = 1
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            items(files) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                currentDir = file
                            } else {
                                onFilePicked(file)
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isFolder = file.isDirectory
                    val fileNameLower = file.name.lowercase()
                    
                    val icon = when {
                        isFolder -> Icons.Default.Folder
                        fileNameLower.endsWith(".apk") -> Icons.Default.Android
                        else -> Icons.Default.InsertDriveFile
                    }
                    
                    val tint = when {
                        isFolder -> MaterialTheme.colorScheme.primary
                        fileNameLower.endsWith(".apk") -> Color(0xFF4CAF50) // Green
                        fileNameLower.endsWith(".xapk") -> Color(0xFF2196F3) // Blue
                        else -> Color(0xFFFF9800) // Orange
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            }
        }
    }
}

private fun translateError(msg: String, isRussian: Boolean): String {
    val upperMsg = msg.uppercase()
    return when {
        "VERSION_DOWNGRADE" in upperMsg -> if (isRussian) "Нельзя установить старую версию поверх новой" else "Cannot downgrade application version"
        "UPDATE_INCOMPATIBLE" in upperMsg || "SIGNATURE" in upperMsg -> if (isRussian) "Конфликт сертификатов (удалите старое приложение)" else "Signature mismatch (uninstall old app first)"
        "NO_MATCHING_ABIS" in upperMsg -> if (isRussian) "Архитектура приложения не поддерживается вашим устройством" else "App architecture is incompatible with this device"
        "INVALID_APK" in upperMsg || "PARSE_FAILED" in upperMsg -> if (isRussian) "Недействительный или повреждённый установочный файл" else "Invalid or corrupted package"
        "INSUFFICIENT_STORAGE" in upperMsg -> if (isRussian) "Недостаточно памяти на устройстве" else "Insufficient storage space"
        "CONFLICTING_PROVIDER" in upperMsg -> if (isRussian) "Конфликт провайдеров файлов (удалите старое приложение)" else "Conflicting provider (uninstall old app first)"
        else -> if (isRussian) "Ошибка: $msg" else "Error: $msg"
    }
}

suspend fun handleFileUri(
    context: Context, 
    uri: Uri, 
    viewModel: MainViewModel
) {
    withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(context, uri)
            viewModel.currentStatus.value = InstallStatus.Preparing(fileName)
            viewModel.installProgress.value = 0.1f
            
            val isArchive = fileName.endsWith(".apks", true) || fileName.endsWith(".xapk", true) || fileName.endsWith(".zip", true) || fileName.endsWith(".apkm", true)

            if (isArchive) {
                installSplitApk(context, uri, viewModel)
            } else if (fileName.endsWith(".apk", true)) {
                installSingleApk(context, uri, viewModel)
            } else {
                viewModel.currentStatus.value = InstallStatus.Error("Unsupported format")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            viewModel.currentStatus.value = InstallStatus.Error(e.localizedMessage ?: "Unknown Error")
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        }
    }
    return result ?: uri.path?.let { File(it).name } ?: "unknown"
}

private fun getApkIcon(context: Context, apkFile: File): Bitmap? {
    try {
        val pm = context.packageManager
        val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
        val appInfo = pi.applicationInfo ?: return null
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath
        return appInfo.loadIcon(pm)?.toBitmapOrNull()
    } catch (e: Exception) {
        return null
    }
}

suspend fun installSingleApk(
    context: Context, 
    uri: Uri, 
    viewModel: MainViewModel
) {
    viewModel.currentStatus.value = InstallStatus.Extracting(1)
    viewModel.installProgress.value = 0.5f
    try {
        var baseApkFile: File? = null
        if (uri.scheme == "file") {
            baseApkFile = File(uri.path!!)
        } else {
            val tempFile = File(context.cacheDir, "temp_base.apk")
            context.contentResolver.openInputStream(uri)?.use { inStr ->
                tempFile.outputStream().use { outStr ->
                    inStr.copyTo(outStr)
                }
            }
            baseApkFile = tempFile
        }
        
        baseApkFile?.let { viewModel.loadedAppIcon.value = getApkIcon(context, it) }

        viewModel.currentStatus.value = InstallStatus.Installing
        viewModel.installProgress.value = 1f
        InstallerCore.install(context, listOf(uri)) { _, _ -> }
    } catch(e: Exception) {
        viewModel.currentStatus.value = InstallStatus.Error(e.localizedMessage ?: "Unknown error")
    }
}

suspend fun installSplitApk(
    context: Context, 
    uri: Uri, 
    viewModel: MainViewModel
) {
    viewModel.currentStatus.value = InstallStatus.Extracting(0)
    viewModel.installProgress.value = 0.3f
    try {
        val tempDir = File(context.cacheDir, "temp_apks")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        
        val uris = mutableListOf<Uri>()
        var extractedBaseApk: File? = null
        
        val inputStream = if (uri.scheme == "file") {
            File(uri.path!!).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }
        
        inputStream?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                var extractedCount = 0
                while (entry != null) {
                    if (entry.name.endsWith(".apk", true)) {
                        val outFile = File(tempDir, entry.name.substringAfterLast("/"))
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                        uris.add(Uri.fromFile(outFile))
                        
                        if (extractedBaseApk == null || outFile.name.contains("base", true)) {
                            extractedBaseApk = outFile
                        }
                        
                        extractedCount++
                        viewModel.currentStatus.value = InstallStatus.Extracting(extractedCount)
                        viewModel.installProgress.value = 0.3f + (0.05f * extractedCount).coerceAtMost(0.4f)
                    } else if (entry.name.endsWith(".obb", true)) {
                        val outFile = File(tempDir, entry.name.substringAfterLast("/"))
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        
        extractedBaseApk?.let { viewModel.loadedAppIcon.value = getApkIcon(context, it) }
        
        // --- OBB Copying Logic ---
        val obbFiles = tempDir.listFiles { f -> f.name.endsWith(".obb", true) } ?: emptyArray()
        if (obbFiles.isNotEmpty() && extractedBaseApk != null) {
            try {
                val pi = context.packageManager.getPackageArchiveInfo(extractedBaseApk!!.absolutePath, 0)
                val packageName = pi?.packageName
                if (packageName != null) {
                    val obbDestDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
                    obbDestDir.mkdirs()
                    for (obb in obbFiles) {
                        viewModel.currentStatus.value = InstallStatus.Preparing("OBB: ${obb.name}")
                        obb.copyTo(File(obbDestDir, obb.name), overwrite = true)
                        obb.delete() // clean up temp
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Ignore OBB copy error and try to install APK anyway
            }
        }
        
        if (uris.isNotEmpty()) {
            viewModel.currentStatus.value = InstallStatus.Installing
            viewModel.installProgress.value = 1f
            InstallerCore.install(context, uris) { _, _ -> }
        } else {
            viewModel.currentStatus.value = InstallStatus.Error("No APKs found inside archive.")
        }
    } catch(e: Exception) {
         viewModel.currentStatus.value = InstallStatus.Error(e.localizedMessage ?: "Zip extract error")
    }
}

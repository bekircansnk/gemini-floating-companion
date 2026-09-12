package com.gemini.floatingcompanion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gemini.floatingcompanion.data.GeminiApiKeyManager
import com.gemini.floatingcompanion.data.PreferencesManager
import com.gemini.floatingcompanion.overlay.FloatingBubbleManager
import com.gemini.floatingcompanion.service.FloatingBubbleService
import com.gemini.floatingcompanion.service.GeminiAccessibilityService
import com.gemini.floatingcompanion.ui.theme.GeminiBlue
import com.gemini.floatingcompanion.ui.theme.GeminiCompanionTheme
import com.gemini.floatingcompanion.ui.theme.GeminiPink
import com.gemini.floatingcompanion.ui.theme.GeminiPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GeminiCompanionTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesManager.getInstance(context) }
    val keyManager = remember { GeminiApiKeyManager.getInstance() }

    var useVaultPool by remember { mutableStateOf(prefs.useVaultPool) }
    var customApiKey by remember { mutableStateOf(prefs.customApiKey) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var activeKeyInfo by remember { mutableStateOf(keyManager.getActiveKeyInfo()) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var testText by remember { mutableStateOf("") }

    // Permission states
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(GeminiAccessibilityService.isRunning()) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasMediaProjection by remember {
        mutableStateOf(
            com.gemini.floatingcompanion.live.MediaProjectionHolder.isProjectionActive() ||
            com.gemini.floatingcompanion.live.MediaProjectionHolder.hasPendingResult()
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            FloatingBubbleService.setMediaProjectionActive(context, true)
            com.gemini.floatingcompanion.live.MediaProjectionHolder.setPendingResult(result.resultCode, result.data!!)
            val proj = com.gemini.floatingcompanion.live.MediaProjectionHolder.getOrCreateMediaProjection(context)
            hasMediaProjection = proj != null
            com.gemini.floatingcompanion.overlay.FloatingBubbleManager.getInstance(context)?.userDeclinedMediaProjection = false
            Toast.makeText(context, "🎧 Dahili Medya Sesi Hazır! (0 Yankı)", Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasAccessibilityPermission = GeminiAccessibilityService.isRunning()
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                hasMediaProjection = com.gemini.floatingcompanion.live.MediaProjectionHolder.isProjectionActive() ||
                    com.gemini.floatingcompanion.live.MediaProjectionHolder.hasPendingResult()
                activeKeyInfo = keyManager.getActiveKeyInfo()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with Gradient Bento Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(GeminiBlue, GeminiPurple, GeminiPink)
                        )
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Gemini Companion",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Cam Bento Akıllı Baloncuk • Canlı Ses Dikte • 6-Key Failover • Akıllı OCR • Canlı Video Çevirisi",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // CENTRAL VAULT & FAILOVER POOL CARD
            Text(
                text = "Gemini API Vault & Failover Havuzu",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    val availableVaultKeys = remember { keyManager.getAllKeysStatus() }

                    if (availableVaultKeys.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = "Vault",
                                    tint = GeminiBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Merkezi Vault Havuzu",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
                            Switch(
                                checked = useVaultPool,
                                onCheckedChange = {
                                    useVaultPool = it
                                    prefs.useVaultPool = it
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (useVaultPool && availableVaultKeys.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.12f))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Aktif: ${activeKeyInfo.name}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF047857)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Key: ${GeminiApiKeyManager.maskKey(activeKeyInfo.key)} • Tür: ${activeKeyInfo.type.uppercase()} • Öncelik: ${activeKeyInfo.priority}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF065F46)
                                )
                                Text(
                                    text = "Başarılı İstek: ${activeKeyInfo.successCount} • Hata/Rotasyon: ${activeKeyInfo.failureCount}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF047857).copy(alpha = 0.8f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Havuz Durumu: ${availableVaultKeys.size} Anahtar devrede. 429 Rate Limit veya kota aşımında sistem sıradaki anahtara sıfır kesintiyle otomatik geçer.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedTextField(
                            value = customApiKey,
                            onValueChange = {
                                customApiKey = it
                                prefs.customApiKey = it
                            },
                            label = { Text("Özel Gemini API Anahtarı") },
                            placeholder = { Text("AIzaSy... veya AQ....") },
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                    Icon(
                                        imageVector = if (isApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Görünürlük"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                isTestingConnection = true
                                connectionTestResult = null
                                scope.launch {
                                    val start = System.currentTimeMillis()
                                    val result = testGeminiApiLive(
                                        apiKey = if (useVaultPool) keyManager.getActiveApiKey() else customApiKey
                                    )
                                    val duration = System.currentTimeMillis() - start
                                    isTestingConnection = false
                                    connectionTestResult = if (result.isSuccess) {
                                        keyManager.reportSuccess(keyManager.getActiveApiKey())
                                        activeKeyInfo = keyManager.getActiveKeyInfo()
                                        "Başarılı (${duration}ms): ${result.getOrNull()}"
                                    } else {
                                        val err = result.exceptionOrNull()?.localizedMessage ?: "Hata"
                                        keyManager.reportFailure(keyManager.getActiveApiKey(), 500, err)
                                        activeKeyInfo = keyManager.getActiveKeyInfo()
                                        "Hata (${duration}ms): $err"
                                    }
                                }
                            },
                            enabled = !isTestingConnection,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Ediliyor...", fontSize = 11.sp)
                            } else {
                                Icon(Icons.Default.Speed, contentDescription = "Test", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("API Test Et", fontSize = 11.sp)
                            }
                        }

                        if (useVaultPool) {
                            Button(
                                onClick = {
                                    activeKeyInfo = keyManager.rotateToNextKey("Manuel Kullanıcı Rotasyonu")
                                    Toast.makeText(context, "Sıradaki Key: ${activeKeyInfo.name}", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GeminiPurple),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Döndür", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Key Döndür", fontSize = 11.sp)
                            }
                        }
                    }

                    connectionTestResult?.let { resText ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                 .fillMaxWidth()
                                 .clip(RoundedCornerShape(10.dp))
                                 .background(if (resText.startsWith("Başarılı")) Color(0xFFECFDF5) else Color(0xFFFEF2F2))
                                 .border(1.dp, if (resText.startsWith("Başarılı")) Color(0xFF10B981) else Color(0xFFEF4444), RoundedCornerShape(10.dp))
                                 .padding(10.dp)
                        ) {
                            Text(
                                text = resText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (resText.startsWith("Başarılı")) Color(0xFF047857) else Color(0xFFB91C1C)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Permission Checklist Section
            Text(
                text = "Gereken İzinler & Sistem Sağlığı",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )

            // 1. Accessibility Service
            PermissionCard(
                title = "1. Erişilebilirlik Servisi",
                description = "Yazı kutularını algılar ve konuşulanları/OCR metinlerini doğrudan alana yazar.",
                isGranted = hasAccessibilityPermission,
                icon = Icons.Default.Security,
                onAction = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Overlay Permission
            PermissionCard(
                title = "2. Diğer Uygulamaların Üzerinde Gösterme",
                description = "Mesaj yazarken cam baloncuğun ekranda belirmesini sağlar.",
                isGranted = hasOverlayPermission,
                icon = Icons.Default.AutoAwesome,
                onAction = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Microphone Permission
            PermissionCard(
                title = "3. Canlı Ses Dikte (Mikrofon)",
                description = "Canlı ses dikte ile konuşurken anında yazıya döker.",
                isGranted = hasMicPermission,
                icon = Icons.Default.Mic,
                onAction = {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Camera Permission
            PermissionCard(
                title = "4. Akıllı Kamera & Belge Tarayıcı",
                description = "Akıllı metin OCR ve PDF belge üretimi için kamera erişimi.",
                isGranted = hasCameraPermission,
                icon = Icons.Default.CameraAlt,
                onAction = {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Spacer(modifier = Modifier.height(10.dp))

                // 5. Internal Audio Capture Permission
                PermissionCard(
                    title = "5. Dahili Sistem Sesi (YouTube / Video)",
                    description = "YouTube ve medya sesini mikrofona gerek kalmadan doğrudan yakalar. Yankıyı ve gürültüyü %100 sıfırlar.",
                    isGranted = hasMediaProjection,
                    icon = Icons.Default.Headphones,
                    onAction = {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
                        mpManager?.createScreenCaptureIntent()?.let {
                            mediaProjectionLauncher.launch(it)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 6. Xiaomi / HyperOS Survivability
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "HyperOS",
                            tint = GeminiPurple
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Xiaomi / HyperOS Dayanıklılık",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Servisin arka planda kapanmaması için 'Otomatik Başlatma' (Autostart) ve Pil Kısıtlaması 'Kısıtlama Yok' seçilmelidir.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            openXiaomiOptimizationSettings(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Pil & Başlangıç Ayarlarını Aç", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Interactive Live Playground / Test Area
            Text(
                text = "Canlı Test & Deneme Alanı",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Aşağıdaki kutuya dokunduğunuzda klavye açılacak ve ekranınızda Cam Baloncuk belirecektir. Baloncuğa tıklayarak canlı dikteyi, uzun basarak OCR kamerasını test edebilirsiniz:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = testText,
                        onValueChange = { testText = it },
                        placeholder = { Text("Buraya dokunun... Baloncuk görünecek!") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Clean Cam Bento Action Grid (2-Row Layout, No Overflow)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                FloatingBubbleService.start(context)
                                FloatingBubbleManager.getInstance(context)?.showBubble()
                                Toast.makeText(context, "Cam Baloncuk ekranda!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Baloncuğu Aç", fontSize = 12.sp, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                FloatingBubbleService.start(context)
                                FloatingBubbleManager.getInstance(context)?.toggleLiveVideoTranslation()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiPurple)
                        ) {
                            Text("Video Çevir", fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            FloatingBubbleManager.getInstance(context)?.hideBubble()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("Baloncuğu Gizle", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isGranted) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = if (isGranted) "Aktif" else "Gerekli",
                        tint = if (isGranted) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                ) {
                    Text("İzin Ver", fontSize = 12.sp)
                }
            }
        }
    }
}

private suspend fun testGeminiApiLive(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) {
        return@withContext Result.failure(Exception("API Anahtarı boş"))
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // 1. Canlı Video Çeviri Sistemi (WebSocket - gemini-3.5-live-translate-preview) Setup Testi (0 Maliyet)
    var videoStatus = "Video Çevirisi: Başarısız"
    var videoSuccess = false
    try {
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val wsRequest = Request.Builder().url(wsUrl).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var wsErrorMsg = ""

        val ws = client.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                android.util.Log.d("ApiTest", "WebSocket opened: ${response.code}")
                val setupJson = JSONObject().apply {
                    val setupObj = JSONObject().apply {
                        put("model", "models/gemini-3.5-live-translate-preview")
                        put("inputAudioTranscription", JSONObject())
                        put("outputAudioTranscription", JSONObject())
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().apply { put("AUDIO") })
                            put("translationConfig", JSONObject().apply {
                                put("targetLanguageCode", "tr")
                                put("echoTargetLanguage", false)
                            })
                        })
                    }
                    put("setup", setupObj)
                }
                val sent = webSocket.send(setupJson.toString())
                android.util.Log.d("ApiTest", "Setup frame sent: $sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                android.util.Log.d("ApiTest", "WebSocket message: $text")
                if (text.contains("setupComplete")) {
                    videoSuccess = true
                    webSocket.close(1000, "Test done")
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val text = bytes.utf8()
                android.util.Log.d("ApiTest", "WebSocket bytes message: $text")
                if (text.contains("setupComplete")) {
                    videoSuccess = true
                    webSocket.close(1000, "Test done")
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("ApiTest", "WebSocket failure: ${t.message}, code: ${response?.code}")
                wsErrorMsg = t.message ?: "Bağlantı hatası"
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("ApiTest", "WebSocket closed: $code, reason: $reason")
                latch.countDown()
            }
        })

        latch.await(6, TimeUnit.SECONDS)
        try { ws.close(1000, "Done") } catch (_: Exception) {}
        videoStatus = if (videoSuccess) "Canlı Video Çevirisi: Aktif" else "Canlı Video Çevirisi: ${wsErrorMsg.ifBlank { "Zaman aşımı" }}"
    } catch (e: Exception) {
        videoStatus = "Canlı Video Çevirisi: ${e.localizedMessage}"
    }

    // 2. Sesli Konuşma & Metin Testi (Ultra düşük kotalı gemini-3.5-flash-lite)
    var speechStatus = "Ses Modeli: Başarısız"
    var speechSuccess = false
    try {
        val testModel = "gemini-3.5-flash-lite"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$testModel:generateContent?key=$apiKey"
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "ping") })
                    })
                })
            })
        }
        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            speechSuccess = true
            speechStatus = "Sesli Konuşma & Metin: Aktif (3.5 Flash-Lite)"
        } else if (response.code == 404) {
            val fbUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val fbResp = client.newCall(Request.Builder().url(fbUrl).post(requestBody).build()).execute()
            if (fbResp.isSuccessful) {
                speechSuccess = true
                speechStatus = "Sesli Konuşma & Metin: Aktif (2.5 Flash)"
            } else {
                speechStatus = "Ses Modeli: HTTP ${fbResp.code}"
            }
        } else {
            speechStatus = "Ses Modeli: HTTP ${response.code}"
        }
    } catch (e: Exception) {
        speechStatus = "Ses Modeli: ${e.localizedMessage}"
    }

    if (videoSuccess && speechSuccess) {
        Result.success("$videoStatus\n$speechStatus")
    } else if (videoSuccess || speechSuccess) {
        Result.success("$videoStatus\n$speechStatus")
    } else {
        Result.failure(Exception("$videoStatus\n$speechStatus"))
    }
}

private fun openXiaomiOptimizationSettings(context: Context) {
    try {
        val intent = Intent().apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}

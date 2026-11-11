package com.thaweekarn.slipsync.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.ui.components.PartialAccessBanner
import com.thaweekarn.slipsync.ui.components.XiaomiHelperSection
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed
import com.thaweekarn.slipsync.ui.viewmodel.OnboardingUiState
import com.thaweekarn.slipsync.ui.viewmodel.OnboardingViewModel
import com.thaweekarn.slipsync.util.XiaomiIntentHelper

import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Login
import coil.compose.AsyncImage

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            OnboardingProgressBar(currentStep = state.currentStep, totalSteps = 5)
        },
        bottomBar = {
            OnboardingBottomNav(
                currentStep = state.currentStep,
                canProceed = when (state.currentStep) {
                    1 -> hasPhotoAccess(context)
                    2 -> state.isGoogleDriveConnected || (state.webAppUrl.isNotBlank() && state.authToken.isNotBlank())
                    3 -> state.selectedFolders.isNotEmpty()
                    4 -> true
                    5 -> true
                    else -> true
                },
                onPrev = { viewModel.prevStep() },
                onNext = {
                    if (state.currentStep == 5) {
                        viewModel.completeOnboarding(onFinished)
                    } else {
                        viewModel.nextStep()
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when (state.currentStep) {
                1 -> Step1Permissions(context = context)
                2 -> Step2WebApp(viewModel = viewModel, state = state)
                3 -> Step3Folders(viewModel = viewModel, state = state)
                4 -> Step4XiaomiOptimization()
                5 -> Step5TestSlip(viewModel = viewModel, state = state)
            }
        }
    }
}

@Composable
private fun OnboardingProgressBar(currentStep: Int, totalSteps: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "เริ่มต้นใช้งาน SlipSync (Setup Wizard)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$currentStep / $totalSteps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (step in 1..totalSteps) {
                val active = step <= currentStep
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

@Composable
private fun Step1Permissions(context: Context) {
    var hasFull by remember { mutableStateOf(hasPhotoAccess(context)) }
    var hasPartial by remember { mutableStateOf(hasPartialPhotoAccess(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasFull = hasPhotoAccess(context)
        hasPartial = hasPartialPhotoAccess(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ขั้นตอนที่ 1: สิทธิ์การเข้าถึงรูปภาพ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Step 1: Storage Permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SlipSync จำเป็นต้องมีสิทธิ์เข้าถึงรูปภาพทั้งหมดในอุปกรณ์ เพื่อคอยตรวจจับเมื่อมีสลิปโอนเงินใหม่ถูกบันทึกลงในเครื่อง โดยไม่มีการดัดแปลงหรือลบรูปภาพใดๆ (Read-Only)",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (hasPartial) {
            PartialAccessBanner(
                onGrantFullAccessClick = { XiaomiIntentHelper.openAppDetailsSettings(context) }
            )
        } else if (hasFull) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StatusGreen.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "ได้รับสิทธิ์เข้าถึงรูปภาพทั้งหมดแล้ว พร้อมดำเนินการต่อ",
                        fontWeight = FontWeight.Bold,
                        color = StatusGreen
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    permissionLauncher.launch(permissions)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("ขอสิทธิ์เข้าถึงรูปภาพ (Grant Permission)", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Step2WebApp(viewModel: OnboardingViewModel, state: OnboardingUiState) {
    var tokenVisible by remember { mutableStateOf(false) }
    var showManualSetup by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ขั้นตอนที่ 2: เชื่อมต่อ Google Drive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Step 2: Connect Google Drive Storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Option A: 1-Tap Google Sign-In (Recommended)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.isGoogleDriveConnected) StatusGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = if (state.isGoogleDriveConnected) StatusGreen else MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "วิธีที่ 1: บัญชี Google (แนะนำ / Recommended)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Text(
                    text = "ลงชื่อเข้าใช้ด้วย Gmail เพื่อเชื่อมต่อ Google Drive อัตโนมัติ สะดวก ปลอดภัย ไม่ต้องวาง URL หรือรหัสผ่าน",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.isGoogleDriveConnected && state.googleUser != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!state.googleUser?.photoUrl?.toString().isNullOrBlank()) {
                            AsyncImage(
                                model = state.googleUser?.photoUrl,
                                contentDescription = "User Avatar",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = StatusGreen
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.googleUser?.displayName ?: state.googleUser?.email ?: "Google User",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = state.googleUser?.email ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.auth_connected_drive),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.signInWithGoogle(context) },
                        enabled = !state.isSigningInGoogle,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isSigningInGoogle) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.auth_signing_in))
                        } else {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.auth_sign_in_google), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!state.authMessage.isNullOrBlank()) {
                    Text(
                        text = state.authMessage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Option B: Manual Web App URL + Token
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showManualSetup = !showManualSetup }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "วิธีที่ 2: ตั้งค่า Web App ด้วยตนเอง (Advanced)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (showManualSetup) "ซ่อน" else "แสดง",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedVisibility(visible = showManualSetup) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = state.webAppUrl,
                    onValueChange = { viewModel.setWebAppUrl(it) },
                    label = { Text(stringResource(R.string.url_label)) },
                    placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = state.authToken,
                    onValueChange = { viewModel.setAuthToken(it) },
                    label = { Text(stringResource(R.string.token_label)) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = state.webAppUrl.isNotBlank() && state.authToken.isNotBlank() && !state.isTestingConnection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.isTestingConnection) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("กำลังทดสอบการเชื่อมต่อ...")
                    } else {
                        Text(stringResource(R.string.action_test_connection), fontWeight = FontWeight.Bold)
                    }
                }

                if (state.connectionSuccess != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val isOk = state.connectionSuccess == true
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isOk) StatusGreen.copy(alpha = 0.12f) else StatusRed.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isOk) Icons.Default.CheckCircle else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isOk) StatusGreen else StatusRed
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.connectionMessage,
                                color = if (isOk) StatusGreen else StatusRed,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step3Folders(viewModel: OnboardingViewModel, state: OnboardingUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ขั้นตอนที่ 3: เลือกโฟลเดอร์สลิป", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Step 3: Select Bank Folders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "ตรวจพบโฟลเดอร์ภาพในเครื่อง กรุณาเลือกโฟลเดอร์แอปธนาคารที่คุณต้องการให้ระบบตรวจสอบ:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.suggestedBankFolders.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bank_folders_detected) + " ⭐",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(items = state.suggestedBankFolders) { folder ->
                    FolderItem(
                        folderName = folder,
                        isSelected = state.selectedFolders.contains(folder),
                        isSuggested = true,
                        onToggle = { viewModel.toggleFolder(folder) }
                    )
                }
            }

            val otherFolders = state.discoveredFolders.filterNot { state.suggestedBankFolders.contains(it) }
            if (otherFolders.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.other_folders),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(items = otherFolders) { folder ->
                    FolderItem(
                        folderName = folder,
                        isSelected = state.selectedFolders.contains(folder),
                        isSuggested = false,
                        onToggle = { viewModel.toggleFolder(folder) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Initial backfill range
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("ดึงสลิปที่มีอยู่แล้วย้อนหลัง (Initial Backfill):", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7, 30).forEach { days ->
                        val selected = state.backfillDays == days
                        FilledTonalButton(
                            onClick = { viewModel.setBackfillDays(days) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("${days}d", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderItem(
    folderName: String,
    isSelected: Boolean,
    isSuggested: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(folderName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (isSuggested) {
                    Text("ตรวจพบแอปธนาคารไทย (Bank slip)", style = MaterialTheme.typography.labelSmall, color = StatusGreen)
                }
            }
        }
    }
}

@Composable
private fun Step4XiaomiOptimization() {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ขั้นตอนที่ 4: ปรับแต่ง Xiaomi / HyperOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Step 4: Xiaomi Resilience", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "ระบบ HyperOS มีการปิดแอปพื้นหลังอย่างเข้มงวด กรุณาเปิดสิทธิ์ Autostart และยกเว้นการประหยัดแบตเตอรี่เพื่อให้แอปทำงานได้อัตโนมัติตลอดเวลา:",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        XiaomiHelperSection()
    }
}

@Composable
private fun Step5TestSlip(viewModel: OnboardingViewModel, state: OnboardingUiState) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.setTestImageUri(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("ขั้นตอนที่ 5: ตรวจสอบและพร้อมใช้งาน", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Step 5: Test & Complete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "การตั้งค่าเรียบร้อยแล้ว! คุณสามารถเลือกภาพสลิปเพื่อทดสอบการอ่าน QR และส่งขึ้น Google Drive ตอนนี้ หรือกด 'เสร็จสิ้น' เพื่อเริ่มการทำงานอัตโนมัติ",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(
            onClick = {
                photoPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (state.testImageUri != null) "เปลี่ยนภาพสลิปทดสอบ" else "เลือกภาพสลิปเพื่อทดสอบ (Pick Test Slip)")
        }

        if (state.testImageUri != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.uploadTestImage() },
                enabled = !state.isTestingUpload,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isTestingUpload) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("กำลังทดสอบส่งสลิป...")
                } else {
                    Text("ทดสอบส่งสลิปนี้ (Upload Test Slip)", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (state.testUploadSuccess != null) {
            Spacer(modifier = Modifier.height(12.dp))
            val isOk = state.testUploadSuccess == true
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOk) StatusGreen.copy(alpha = 0.12f) else StatusRed.copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isOk) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (isOk) StatusGreen else StatusRed
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = state.testUploadMessage,
                        color = if (isOk) StatusGreen else StatusRed,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingBottomNav(
    currentStep: Int,
    canProceed: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentStep > 1) {
            TextButton(onClick = onPrev) {
                Text("ย้อนกลับ (Back)")
            }
        } else {
            Spacer(modifier = Modifier.width(60.dp))
        }

        Button(
            onClick = onNext,
            enabled = canProceed,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (currentStep == 5) "เสร็จสิ้น (Finish)" else "ถัดไป (Next)", fontWeight = FontWeight.Bold)
        }
    }
}

private fun hasPhotoAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun hasPartialPhotoAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val partial = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        partial && !full
    } else false
}

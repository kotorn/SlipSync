package com.thaweekarn.slipsync.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.ui.components.StatusChip
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed
import com.thaweekarn.slipsync.ui.viewmodel.DetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlipDetailScreen(
    sha256: String,
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val slip by viewModel.slipState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sha256) {
        viewModel.loadSlip(sha256)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        if (slip == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("กำลังโหลดข้อมูลสลิป...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val item = slip!!
            val imageUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                item.mediaStoreId
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. High-Res Image Preview Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Slip Image Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 2. Header and Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.sourceFolder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    StatusChip(status = item.status)
                }

                // 3. Status Timeline
                TimelineSection(slip = item)

                // 4. Metadata Details Card (SHA-256, QR payload)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // SHA-256
                        val shortSha = if (item.sha256.length >= 8) item.sha256.take(8) else item.sha256
                        DetailRow(
                            label = stringResource(R.string.detail_sha256),
                            value = "$shortSha (${item.sha256.length} chars)",
                            copyText = item.sha256,
                            context = context
                        )

                        // QR Payload
                        if (item.hasQr && !item.slipQr.isNullOrBlank()) {
                            DetailRow(
                                label = stringResource(R.string.detail_qr_payload),
                                value = item.slipQr,
                                copyText = item.slipQr,
                                context = context
                            )
                        } else {
                            Column {
                                Text(
                                    text = stringResource(R.string.detail_qr_payload),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "ไม่พบ QR Code ในภาพ (ใช้รหัส SHA-256 สำหรับตรวจสอบ)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Last error (if any)
                        if (!item.lastError.isNullOrBlank()) {
                            Column {
                                Text(
                                    text = "สาเหตุข้อผิดพลาด (Details):",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StatusRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.lastError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StatusRed
                                )
                            }
                        }
                    }
                }

                // 5. Actions
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Open in Google Drive button (if uploaded)
                    if (!item.driveFileId.isNullOrBlank()) {
                        Button(
                            onClick = {
                                val url = "https://drive.google.com/file/d/${item.driveFileId}/view"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot open Drive URL", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_open_drive), fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.retry()
                                Toast.makeText(context, "กำลังส่งสลิปใหม่...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_retry))
                        }

                        if (item.status == SlipEntity.STATUS_SKIPPED_IGNORED) {
                            OutlinedButton(
                                onClick = { viewModel.unignore() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(R.string.action_undo))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    viewModel.ignore()
                                    Toast.makeText(context, "ละเว้นสลิปนี้แล้ว", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.action_ignore))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineSection(slip: SlipEntity) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val takenStr = dateFormat.format(Date(slip.takenAt))
    val uploadedStr = slip.uploadedAt?.let { dateFormat.format(Date(it)) } ?: "-"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ลำดับเหตุการณ์ (Status Timeline):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

            // Step 1: Detected
            TimelineItem(
                title = stringResource(R.string.detail_timeline_detected),
                subtitle = takenStr,
                isCompleted = true,
                color = StatusGreen
            )

            // Step 2: QR Reading
            if (slip.hasQr) {
                TimelineItem(
                    title = stringResource(R.string.detail_timeline_qr_ok),
                    subtitle = "อ่านข้อมูล QR Code สำเร็จ",
                    isCompleted = true,
                    color = StatusGreen
                )
            } else {
                TimelineItem(
                    title = "ไม่พบ QR (No QR found)",
                    subtitle = "ใช้รหัส SHA-256 สำหรับตรวจจับการซ้ำแทน",
                    isCompleted = true,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Step 3: Cloud Upload
            when {
                slip.isSuccess -> {
                    TimelineItem(
                        title = stringResource(R.string.detail_timeline_uploaded),
                        subtitle = uploadedStr,
                        isCompleted = true,
                        color = StatusGreen
                    )
                }
                slip.status == SlipEntity.STATUS_FAILED -> {
                    TimelineItem(
                        title = "อัปโหลดไม่สำเร็จ (Upload Failed)",
                        subtitle = "พยายามแล้ว ${slip.attempts} ครั้ง: ${slip.lastError ?: "Unknown error"}",
                        isCompleted = false,
                        color = StatusRed
                    )
                }
                else -> {
                    TimelineItem(
                        title = "รอดำเนินการส่งขึ้นคลาวด์ (Pending Upload)",
                        subtitle = "ระบบจะส่งขึ้น Google Drive เมื่อเครือข่ายพร้อม",
                        isCompleted = false,
                        color = StatusAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(
    title: String,
    subtitle: String,
    isCompleted: Boolean,
    color: Color
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    copyText: String,
    context: Context
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Slip Detail", copyText))
                    Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
            }
        }
    }
}

package com.thaweekarn.slipsync.ui.components

import android.content.ContentUris
import android.provider.MediaStore
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.local.SlipEntity
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGray
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SlipItemRow(
    slip: SlipEntity,
    onItemClick: (SlipEntity) -> Unit,
    onRetry: (SlipEntity) -> Unit,
    onIgnore: (SlipEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onIgnore(slip)
                    false // Don't remove directly; let DB flow update
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onRetry(slip)
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> StatusGray
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primary
                else -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.VisibilityOff
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Refresh
                else -> null
            }
            val label = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> stringResource(R.string.action_ignore)
                SwipeToDismissBoxValue.EndToStart -> stringResource(R.string.action_retry)
                else -> ""
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (icon != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) {
        SlipCardContent(slip = slip, onClick = { onItemClick(slip) })
    }
}

@Composable
private fun SlipCardContent(
    slip: SlipEntity,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageUri = ContentUris.withAppendedId(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        slip.mediaStoreId
    )

    val dateFormat = SimpleDateFormat("HH:mm:ss • dd/MM", Locale.getDefault())
    val dateStr = dateFormat.format(Date(slip.takenAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with fallback
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slip.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = slip.sourceFolder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                // QR status indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (slip.hasQr) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.status_has_qr),
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusGreen,
                            fontSize = 11.sp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.status_no_qr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status chip
            StatusChip(status = slip.status)
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (bgColor, textColor, textRes, icon) = when (status) {
        SlipEntity.STATUS_UPLOADED -> Quadruple(
            StatusGreen.copy(alpha = 0.15f),
            StatusGreen,
            R.string.status_uploaded,
            Icons.Default.Check
        )
        SlipEntity.STATUS_UPLOADED_NO_QR -> Quadruple(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.secondary,
            R.string.status_uploaded,
            Icons.Default.Check
        )
        SlipEntity.STATUS_PENDING, SlipEntity.STATUS_UPLOADING -> Quadruple(
            StatusAmber.copy(alpha = 0.15f),
            StatusAmber,
            if (status == SlipEntity.STATUS_UPLOADING) R.string.status_uploading else R.string.status_pending,
            Icons.Default.HourglassEmpty
        )
        SlipEntity.STATUS_FAILED -> Quadruple(
            StatusRed.copy(alpha = 0.15f),
            StatusRed,
            R.string.status_failed,
            Icons.Default.ErrorOutline
        )
        SlipEntity.STATUS_SKIPPED_IGNORED -> Quadruple(
            StatusGray.copy(alpha = 0.15f),
            StatusGray,
            R.string.status_ignored,
            Icons.Default.Block
        )
        else -> Quadruple(
            StatusGray.copy(alpha = 0.15f),
            StatusGray,
            R.string.status_uploaded,
            Icons.Default.Check
        )
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(textRes),
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

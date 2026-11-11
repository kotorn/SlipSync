package com.thaweekarn.slipsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.repository.HealthActionType
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed

enum class HealthCardState {
    GREEN_OK,
    AMBER_PENDING,
    RED_ACTION_NEEDED
}

@Composable
fun HealthCard(
    state: HealthCardState,
    title: String,
    description: String,
    actionType: HealthActionType? = null,
    onFixClick: (HealthActionType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (bgColor, iconColor, icon) = when (state) {
        HealthCardState.GREEN_OK -> Triple(
            StatusGreen.copy(alpha = 0.12f),
            StatusGreen,
            Icons.Default.CheckCircle
        )
        HealthCardState.AMBER_PENDING -> Triple(
            StatusAmber.copy(alpha = 0.12f),
            StatusAmber,
            Icons.Default.Warning
        )
        HealthCardState.RED_ACTION_NEEDED -> Triple(
            StatusRed.copy(alpha = 0.12f),
            StatusRed,
            Icons.Default.Error
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state == HealthCardState.RED_ACTION_NEEDED && actionType != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { onFixClick(actionType) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = StatusRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.action_fix), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

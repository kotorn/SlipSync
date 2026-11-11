package com.thaweekarn.slipsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed

enum class FilterStatus {
    ALL,
    UPLOADED,
    PENDING,
    FAILED
}

@Composable
fun CounterChips(
    todayUploadedCount: Int,
    pendingCount: Int,
    failedCount: Int,
    currentFilter: FilterStatus,
    onFilterChange: (FilterStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CounterItem(
            count = todayUploadedCount,
            label = stringResource(R.string.counter_uploaded_today),
            accentColor = StatusGreen,
            isSelected = currentFilter == FilterStatus.UPLOADED,
            onClick = {
                onFilterChange(if (currentFilter == FilterStatus.UPLOADED) FilterStatus.ALL else FilterStatus.UPLOADED)
            },
            modifier = Modifier.weight(1f)
        )

        CounterItem(
            count = pendingCount,
            label = stringResource(R.string.counter_pending),
            accentColor = StatusAmber,
            isSelected = currentFilter == FilterStatus.PENDING,
            onClick = {
                onFilterChange(if (currentFilter == FilterStatus.PENDING) FilterStatus.ALL else FilterStatus.PENDING)
            },
            modifier = Modifier.weight(1f)
        )

        CounterItem(
            count = failedCount,
            label = stringResource(R.string.counter_failed),
            accentColor = StatusRed,
            isSelected = currentFilter == FilterStatus.FAILED,
            onClick = {
                onFilterChange(if (currentFilter == FilterStatus.FAILED) FilterStatus.ALL else FilterStatus.FAILED)
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CounterItem(
    count: Int,
    label: String,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        accentColor.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val borderColor = if (isSelected) accentColor else Color.Transparent

    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

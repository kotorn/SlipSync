package com.thaweekarn.slipsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.repository.HealthActionType
import com.thaweekarn.slipsync.data.repository.HealthCheckItem
import com.thaweekarn.slipsync.data.repository.HealthStatus
import com.thaweekarn.slipsync.ui.theme.StatusAmber
import com.thaweekarn.slipsync.ui.theme.StatusGreen
import com.thaweekarn.slipsync.ui.theme.StatusRed
import com.thaweekarn.slipsync.ui.viewmodel.SettingsViewModel
import com.thaweekarn.slipsync.util.XiaomiIntentHelper
import com.thaweekarn.slipsync.worker.WorkScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    viewModel: SettingsViewModel,
    onNavigateToSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val checks by viewModel.healthChecks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshHealthChecks()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_check_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshHealthChecks() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "ตรวจสอบระบบการทำงานพื้นหลังและความปลอดภัย (6 Checkpoints):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(checks, key = { it.id }) { item ->
                HealthCheckCard(
                    item = item,
                    onFixClick = {
                        when (item.actionType) {
                            HealthActionType.PERMISSION_STORAGE -> XiaomiIntentHelper.openAppDetailsSettings(context)
                            HealthActionType.SETTINGS_WEB_APP, HealthActionType.SETTINGS_TOKEN -> onNavigateToSettings()
                            HealthActionType.XIAOMI_BATTERY -> XiaomiIntentHelper.openBatterySettings(context)
                            HealthActionType.SYNC_NOW -> {
                                WorkScheduler.enqueueSyncWork(context)
                                viewModel.refreshHealthChecks()
                            }
                            HealthActionType.SYSTEM_NETWORK -> {}
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HealthCheckCard(
    item: HealthCheckItem,
    onFixClick: () -> Unit
) {
    val (icon, color) = when (item.status) {
        HealthStatus.PASS -> Pair(Icons.Default.CheckCircle, StatusGreen)
        HealthStatus.WARNING -> Pair(Icons.Default.Warning, StatusAmber)
        HealthStatus.FAIL -> Pair(Icons.Default.Error, StatusRed)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.status != HealthStatus.PASS) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onFixClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (item.status == HealthStatus.FAIL) StatusRed else StatusAmber,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.action_fix), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

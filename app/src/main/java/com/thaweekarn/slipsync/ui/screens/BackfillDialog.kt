package com.thaweekarn.slipsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thaweekarn.slipsync.R

@Composable
fun BackfillDialog(
    initialDays: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(1, 3, 7, 30)
    var selectedDays by remember { mutableStateOf(if (initialDays in options) initialDays else 7) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.action_backfill),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "เลือกช่วงเวลาย้อนหลังสำหรับสลิปที่มีอยู่แล้วในโฟลเดอร์ที่เลือก (Select backfill range for existing slips):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                options.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDays = days }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedDays == days),
                            onClick = { selectedDays = days }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (days == 1) "ย้อนหลัง 1 วัน (Last 24 hours)" else "ย้อนหลัง $days วัน (Last $days days)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(selectedDays)
                    onDismiss()
                }
            ) {
                Text("ยืนยัน (Confirm)", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ยกเลิก (Cancel)")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSaveApiKey: (String) -> Unit,
    onSavePollInterval: (Int) -> Unit,
    onSaveGnbBitLength: (Int) -> Unit,
    onSaveIconStyle: (String) -> Unit,
    onBackupDb: () -> Unit,
    onRestoreDb: () -> Unit,
    onImportCsv: (Uri) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE) }
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onImportCsv) }

    var openCellIdKey by remember { mutableStateOf(prefs.getString("opencellid_key", "") ?: "") }
    var pollInterval by remember { mutableStateOf(prefs.getInt("poll_interval", 3)) }
    var gnbBits by remember { mutableStateOf(prefs.getInt("gnb_bits", 24)) }
    var selectedIconStyle by remember { mutableStateOf(prefs.getString("icon_style", "dBm") ?: "dBm") }
    var batchingEnabled by remember { mutableStateOf(prefs.getBoolean("batching_enabled", true)) }
    var batchInterval by remember { mutableStateOf(prefs.getInt("batch_interval", 60)) }

    // Alert triggers states
    var alertOnBand by remember { mutableStateOf(prefs.getBoolean("alert_band", false)) }
    var alertOnTech by remember { mutableStateOf(prefs.getBoolean("alert_tech", false)) }
    var alertOnRsrp by remember { mutableStateOf(prefs.getBoolean("alert_rsrp", false)) }
    var alertOnUnmapped by remember { mutableStateOf(prefs.getBoolean("alert_unmapped", false)) }
    var alertOn5gDrop by remember { mutableStateOf(prefs.getBoolean("alert_5g_drop", true)) }
    var logDropCoords by remember { mutableStateOf(prefs.getBoolean("log_drop_coords", true)) }
    var alertVibrate by remember { mutableStateOf(prefs.getBoolean("alert_vibrate", true)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Monitoring Settings",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        // API Key Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OpenCelliD Integration",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    val isConfigured = openCellIdKey.isNotBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isConfigured) Color(0xFF10B981).copy(alpha = 0.18f)
                                else Color(0xFFEF4444).copy(alpha = 0.18f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isConfigured) "CONFIGURED" else "NOT SET",
                            color = if (isConfigured) Color(0xFF10B981) else Color(0xFFEF4444),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "Real-world cell towers resolve to an address only when this key is set — " +
                            "without it, almost every tower you connect to will show as \"Unmapped.\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
                TextField(
                    value = openCellIdKey,
                    onValueChange = {
                        openCellIdKey = it
                        prefs.edit().putString("opencellid_key", it).apply()
                        onSaveApiKey(it)
                    },
                    placeholder = { Text("Enter OpenCelliD API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Row(
                    modifier = Modifier
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.opencellid.org/register"))
                            context.startActivity(intent)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Get a free API key at opencellid.org",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open OpenCelliD sign-up page",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Config Params Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Hardware Resolution",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                // Polling Interval
                Column {
                    Text(
                        text = "Polling Interval: $pollInterval seconds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Slider(
                        value = pollInterval.toFloat(),
                        onValueChange = {
                            pollInterval = it.toInt()
                            prefs.edit().putInt("poll_interval", pollInterval).apply()
                            onSavePollInterval(pollInterval)
                        },
                        valueRange = 1f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF10B981),
                            activeTrackColor = Color(0xFF10B981)
                        )
                    )
                }

                Divider(color = Color(0xFF334155))

                // gNB Bits
                Column {
                    Text(
                        text = "5G gNodeB Decode Mask: $gnbBits bits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Standard US Carriers (T-Mobile/Verizon) typically use 24 bits. AT&T can fluctuate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Slider(
                        value = gnbBits.toFloat(),
                        onValueChange = {
                            gnbBits = it.toInt()
                            prefs.edit().putInt("gnb_bits", gnbBits).apply()
                            onSaveGnbBitLength(gnbBits)
                        },
                        valueRange = 20f..28f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF38BDF8)
                        )
                    )
                }
            }
        }

        // Battery Optimization Section
        Card(
            modifier = Modifier.fillMaxWidth().testTag("battery_optimization_card"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Power & Battery Optimization",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Batch polling mode wakes up sensors periodically to take a burst of cell tower measurements, reducing continuous CPU and GPS battery drain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Polling Batching",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Switch(
                        checked = batchingEnabled,
                        onCheckedChange = {
                            batchingEnabled = it
                            prefs.edit().putBoolean("batching_enabled", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF10B981),
                            checkedTrackColor = Color(0xFF065F46)
                        ),
                        modifier = Modifier.testTag("batching_enabled_switch")
                    )
                }

                if (batchingEnabled) {
                    Divider(color = Color(0xFF334155))

                    Column {
                        Text(
                            text = "Batch Wake-up Interval: $batchInterval seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Slider(
                            value = batchInterval.toFloat(),
                            onValueChange = {
                                batchInterval = it.toInt()
                                prefs.edit().putInt("batch_interval", batchInterval).apply()
                            },
                            valueRange = 30f..300f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF10B981),
                                activeTrackColor = Color(0xFF10B981)
                            ),
                            modifier = Modifier.testTag("batch_interval_slider")
                        )
                    }
                }
            }
        }

        // Notification Icon Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Status-Bar Icon Style",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose what is drawn in real-time onto the small foreground notification icon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )

                val styles = listOf("dBm", "band", "tech")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styles.forEach { style ->
                        Button(
                            onClick = {
                                selectedIconStyle = style
                                prefs.edit().putString("icon_style", style).apply()
                                onSaveIconStyle(style)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedIconStyle == style) Color(0xFF10B981) else Color(0xFF0F172A)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(style.uppercase(), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Alerts Rules Engine
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Notification Alert Rules",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                AlertSwitchRow(
                    label = "Alert on Band Handover",
                    state = alertOnBand,
                    testTag = "alert_on_band_switch"
                ) {
                    alertOnBand = it
                    prefs.edit().putBoolean("alert_band", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Network Shift (SA/NSA)",
                    state = alertOnTech,
                    testTag = "alert_on_tech_switch"
                ) {
                    alertOnTech = it
                    prefs.edit().putBoolean("alert_tech", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Critical Signal (< -110 dBm)",
                    state = alertOnRsrp,
                    testTag = "alert_on_rsrp_switch"
                ) {
                    alertOnRsrp = it
                    prefs.edit().putBoolean("alert_rsrp", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on Unmapped Tower Connect",
                    state = alertOnUnmapped,
                    testTag = "alert_on_unmapped_switch"
                ) {
                    alertOnUnmapped = it
                    prefs.edit().putBoolean("alert_unmapped", it).apply()
                }
                AlertSwitchRow(
                    label = "Alert on 5G SA to LTE Drop",
                    state = alertOn5gDrop,
                    testTag = "alert_on_5g_drop_switch"
                ) {
                    alertOn5gDrop = it
                    prefs.edit().putBoolean("alert_5g_drop", it).apply()
                }
                AlertSwitchRow(
                    label = "Log 5G Drop Coordinates",
                    state = logDropCoords,
                    testTag = "log_drop_coords_switch"
                ) {
                    logDropCoords = it
                    prefs.edit().putBoolean("log_drop_coords", it).apply()
                }
                AlertSwitchRow(
                    label = "Enable Haptic Vibration",
                    state = alertVibrate,
                    testTag = "alert_vibrate_switch"
                ) {
                    alertVibrate = it
                    prefs.edit().putBoolean("alert_vibrate", it).apply()
                }
            }
        }

        // Database Backup & Restore
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onBackupDb,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Backup")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Backup DB")
                    }

                    Button(
                        onClick = onRestoreDb,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Restore, contentDescription = "Restore")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore DB")
                    }
                }

                Divider(color = Color(0xFF334155))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Bulk Tower Import",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Import a CSV of known towers (radio, mcc, mnc, area, cid, lat, lon, range, address) " +
                                "so they resolve locally without needing an API lookup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { csvImportLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileUpload, contentDescription = "Import CSV")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Tower CSV")
                    }
                }
            }
        }
    }
}

@Composable
fun AlertSwitchRow(
    label: String,
    state: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = state,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF10B981),
                checkedTrackColor = Color(0xFF10B981).copy(alpha = 0.5f)
            )
        )
    }
}

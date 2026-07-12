package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.CellLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<CellLog>,
    onDeleteLog: (CellLog) -> Unit,
    onClearAllLogs: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var techFilter by remember { mutableStateOf("All") }
    var selectedLogForDetail by remember { mutableStateOf<CellLog?>(null) }

    // Filtered logs
    val filteredLogs = logs.filter { log ->
        val matchesSearch = log.address.contains(searchQuery, ignoreCase = true) ||
                log.band.contains(searchQuery, ignoreCase = true) ||
                "${log.cellId}".contains(searchQuery) ||
                "${log.nodebId}".contains(searchQuery)
        val matchesTech = techFilter == "All" || log.tech.contains(techFilter, ignoreCase = true)
        matchesSearch && matchesTech
    }

    // Stats calculations
    val uniqueGnbs = logs.map { it.nodebId }.distinct().size
    val saCount = logs.count { it.tech.contains("SA", ignoreCase = true) }
    val totalCount = logs.size
    val timeOnSaPercent = if (totalCount > 0) (saCount.toFloat() / totalCount) * 100 else 0f

    val bandDistribution = logs.groupBy { it.band }.mapValues { it.value.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Search & Filter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search logs (CID, address, band)") },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(8.dp),
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) }
            )

            // Tech filter segmented buttons style
            Box {
                Button(
                    onClick = {
                        techFilter = when (techFilter) {
                            "All" -> "5G"
                            "5G" -> "LTE"
                            else -> "All"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(techFilter, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Session Stats Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Session Statistics", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatMetric(label = "Unique Towers", value = "$uniqueGnbs")
                    StatMetric(label = "Time-on-SA", value = String.format(Locale.US, "%.1f%%", timeOnSaPercent))
                    StatMetric(label = "Logged Events", value = "$totalCount")
                }

                if (bandDistribution.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Band Distribution", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    BandDistributionPieChart(bandDistribution.toList().take(5).toMap()) // Limit to top 5 for neatness
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Export Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExportButton(label = "CSV", icon = Icons.Default.TableChart, onClick = { exportCsv(context, logs) })
            ExportButton(label = "KML", icon = Icons.Default.Map, onClick = { exportKml(context, logs) })
            ExportButton(label = "GeoJSON", icon = Icons.Default.Public, onClick = { exportGeoJson(context, logs) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Logs List
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Log Entries (${filteredLogs.size})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            if (logs.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    color = Color(0xFFEF4444),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onClearAllLogs() }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredLogs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (logs.isEmpty()) Icons.Default.History else Icons.Default.Search,
                    contentDescription = null,
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (logs.isEmpty()) {
                        "No towers logged yet"
                    } else {
                        "No logs match your search"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (logs.isEmpty()) {
                        "Cell tower connections are recorded automatically while monitoring is active."
                    } else {
                        "Try a different search term or tech filter."
                    },
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs) { log ->
                    LogItemCard(log = log, onClick = { selectedLogForDetail = log }, onDelete = { onDeleteLog(log) })
                }
            }
        }
    }

    // Detail Dialog
    if (selectedLogForDetail != null) {
        val detail = selectedLogForDetail!!
        AlertDialog(
            onDismissRequest = { selectedLogForDetail = null },
            confirmButton = {
                TextButton(onClick = { selectedLogForDetail = null }) {
                    Text("Close")
                }
            },
            title = { Text("Log Entry Details") },
            text = {
                Column {
                    Text("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(detail.timestamp))}", fontWeight = FontWeight.Bold)
                    Text("Technology: ${detail.tech}")
                    Text("Carrier: ${detail.operatorName} (${detail.mcc}-${detail.mnc})")
                    Text("Cell ID: ${detail.cellId}")
                    Text("gNB / eNB: ${detail.nodebId} Sector: ${detail.sectorId}")
                    Text("PCI: ${detail.pci} TAC: ${detail.tac}")
                    Text("Operating Band: ${detail.band}")
                    Text("Metrics: RSRP: ${detail.rsrp} dBm | SINR: ${detail.sinr} dB")
                    Text("GPS Coordinates: ${String.format(Locale.US, "%.5f, %.5f", detail.lat, detail.lon)}")
                    Text("Resolved Address: ${detail.address}")
                    Text("Data Source: ${detail.source}")
                }
            }
        )
    }
}

@Composable
fun StatMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BandDistributionPieChart(distribution: Map<String, Int>) {
    val total = distribution.values.sum().toFloat()
    if (total == 0f) return

    val colors = listOf(
        Color(0xFF38BDF8), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFEC4899), Color(0xFF8B5CF6)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(60.dp)) {
            var startAngle = 0f
            distribution.entries.forEachIndexed { index, entry ->
                val sweepAngle = (entry.value / total) * 360f
                val color = colors[index % colors.size]
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true
                )
                startAngle += sweepAngle
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            distribution.entries.forEachIndexed { index, entry ->
                val color = colors[index % colors.size]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${entry.key.substringBefore(" ")}: ${String.format(Locale.US, "%.1f%%", (entry.value / total) * 100)}",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.ExportButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun LogItemCard(log: CellLog, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.tech,
                        color = if (log.tech.contains("5G")) Color(0xFF10B981) else Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = log.band, color = Color.Gray, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = log.address, color = Color.White, fontSize = 13.sp, maxLines = 1)
                Text(
                    text = "CID: ${log.cellId} | gNB: ${log.nodebId} | RSRP: ${log.rsrp} dBm",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete Log",
                tint = Color(0xFFEF4444).copy(alpha = 0.8f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onDelete() }
            )
        }
    }
}

// --- EXPORT FUNCTIONS ---

private fun exportCsv(context: Context, logs: List<CellLog>) {
    if (logs.isEmpty()) {
        Toast.makeText(context, "No logs to export", Toast.LENGTH_SHORT).show()
        return
    }
    val csv = StringBuilder("Timestamp,Tech,Operator,MCC,MNC,CellID,gNB_eNB,Sector,PCI,TAC,Band,RSRP,SINR,UserLat,UserLon,TowerLat,TowerLon,Address,Source\n")
    logs.forEach { log ->
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(log.timestamp))
        csv.append("\"$date\",\"${log.tech}\",\"${log.operatorName}\",\"${log.mcc}\",\"${log.mnc}\",${log.cellId},${log.nodebId},${log.sectorId},${log.pci},${log.tac},\"${log.band}\",${log.rsrp},${log.sinr},${log.lat},${log.lon},${log.towerLat ?: 0.0},${log.towerLon ?: 0.0},\"${log.address}\",\"${log.source}\"\n")
    }
    shareFile(context, csv.toString(), "towerlock_logs.csv", "text/csv")
}

private fun exportKml(context: Context, logs: List<CellLog>) {
    if (logs.isEmpty()) return
    val kml = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
    logs.forEach { log ->
        kml.append("<Placemark>\n")
        kml.append("  <name>${log.tech} (${log.nodebId})</name>\n")
        kml.append("  <description>Band: ${log.band}\nAddress: ${log.address}\nRSRP: ${log.rsrp} dBm</description>\n")
        kml.append("  <Point>\n")
        kml.append("    <coordinates>${log.lon},${log.lat},0</coordinates>\n")
        kml.append("  </Point>\n")
        kml.append("</Placemark>\n")
    }
    kml.append("</Document>\n</kml>")
    shareFile(context, kml.toString(), "towerlock_logs.kml", "application/vnd.google-earth.kml+xml")
}

private fun exportGeoJson(context: Context, logs: List<CellLog>) {
    if (logs.isEmpty()) return
    val json = StringBuilder("{\n\"type\": \"FeatureCollection\",\n\"features\": [\n")
    logs.forEachIndexed { index, log ->
        json.append("  {\n")
        json.append("    \"type\": \"Feature\",\n")
        json.append("    \"properties\": {\n")
        json.append("      \"tech\": \"${log.tech}\",\n")
        json.append("      \"band\": \"${log.band}\",\n")
        json.append("      \"rsrp\": ${log.rsrp},\n")
        json.append("      \"address\": \"${log.address}\"\n")
        json.append("    },\n")
        json.append("    \"geometry\": {\n")
        json.append("      \"type\": \"Point\",\n")
        json.append("      \"coordinates\": [${log.lon}, ${log.lat}]\n")
        json.append("    }\n")
        json.append("  }${if (index < logs.size - 1) "," else ""}\n")
    }
    json.append("]\n}")
    shareFile(context, json.toString(), "towerlock_logs.geojson", "application/geo+json")
}

private fun shareFile(context: Context, content: String, filename: String, mimeType: String) {
    try {
        val file = File(context.cacheDir, filename)
        file.writeText(content)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export TowerLock Logs"))
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

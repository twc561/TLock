package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.telephony.CellModel
import com.example.location.LocationTracker
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    cell: CellModel,
    userLat: Double?,
    userLon: Double?,
    towerLat: Double?,
    towerLon: Double?,
    resolvedAddress: String,
    confidenceMeters: Int,
    deviceHeading: Float,
    rsrpHistory: List<Int>,
    onSnapshotClick: () -> Unit
) {
    var activeMetricInfo by remember { mutableStateOf<MetricInfo?>(null) }

    val distance = if (userLat != null && userLon != null && towerLat != null && towerLon != null) {
        LocationTracker.calculateDistance(userLat, userLon, towerLat, towerLon)
    } else {
        null
    }

    val bearing = if (userLat != null && userLon != null && towerLat != null && towerLon != null) {
        LocationTracker.calculateBearing(userLat, userLon, towerLat, towerLon)
    } else {
        0f
    }

    val isSuspect = distance != null && cell.distanceEstimateMeters > 0 && 
            (distance > cell.distanceEstimateMeters * 2 || cell.distanceEstimateMeters > distance * 2)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Cosmic Slate dark background
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (cell.cellId <= 0) {
            AcquiringSignalState()
            return@Column
        }

        // Hero Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderLogo(
                        operatorName = cell.operatorName,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cell.operatorName ?: "Carrier",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Serving Cell Identity",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    // Technology Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(cell.signalGradeColorHex))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cell.tech,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Street Address Hero Text
                Text(
                    text = if (towerLat != null) resolvedAddress else "Locating Serving Tower...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Accuracy",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (towerLat != null) "Confidence: ±$confidenceMeters m" else "Calculating triangulation metrics",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF38BDF8)
                    )
                }

                if (isSuspect) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Suspect Position",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Position suspect: TA distance mismatch (>2x)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Metrics Grid (Responsive 2x2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MetricCard(
                    title = "RSRP",
                    value = "${cell.rsrp} dBm",
                    sub = "Ref. Signal Rx Power",
                    color = Color(cell.signalGradeColorHex),
                    icon = Icons.Default.SignalCellularAlt,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "RSRP (Reference Signal Received Power)",
                            "RSRP is the power of the LTE/5G Reference Signals spread over the full bandwidth. It is the primary metric for signal strength.\n\n" +
                                    "Excellent: > -80 dBm\n" +
                                    "Good: -80 to -95 dBm\n" +
                                    "Fair: -95 to -110 dBm\n" +
                                    "Poor: < -110 dBm"
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "SINR",
                    value = "${cell.sinr} dB",
                    sub = "Signal-to-Interference-Noise",
                    color = Color(0xFF38BDF8),
                    icon = Icons.Default.NetworkWifi,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "SINR (Signal-to-Interference-plus-Noise Ratio)",
                            "SINR measures signal quality, taking into account noise and interference from other towers. High SINR means faster data speeds.\n\n" +
                                    "Excellent: > 15 dB\n" +
                                    "Good: 8 to 15 dB\n" +
                                    "Fair: 2 to 8 dB\n" +
                                    "Poor: < 2 dB"
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "Band",
                    value = cell.bandName.substringBefore(" ("),
                    sub = cell.bandName.substringAfter("(").replace(")", ""),
                    color = Color(0xFFF59E0B),
                    icon = Icons.Default.SettingsInputAntenna,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "Carrier Band",
                            "The operating frequency channel of the serving tower. Low bands (600/700MHz) travel far but are slower. Mid bands (2.5GHz/3.7GHz) offer high speeds."
                        )
                    }
                )
            }
            item {
                MetricCard(
                    title = "Distance",
                    value = if (distance != null) String.format(Locale.US, "%.0f m", distance) else "--- m",
                    sub = "TA Ring: ${String.format(Locale.US, "%.0f m", cell.distanceEstimateMeters)}",
                    color = Color(0xFFEC4899),
                    icon = Icons.Default.DirectionsRun,
                    onClick = {
                        activeMetricInfo = MetricInfo(
                            "Distance & Timing Advance",
                            "Distance calculated using GPS coordinates of your phone and the estimated cell tower location.\n\n" +
                                    "Timing Advance (TA) is a hardware-reported step value representing propagation delay. On 5G, each step is ~9.24m."
                        )
                    }
                )
            }

            // Carrier Aggregation Card
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val caActive = cell.activeCarriers.size > 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Carrier Aggregation (CA)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (caActive) Color(0xFF10B981).copy(alpha = 0.2f)
                                        else Color(0xFF64748B).copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (caActive) "${cell.activeCarriers.size}CC ACTIVE" else "STANDBY",
                                    color = if (caActive) Color(0xFF10B981) else Color(0xFF94A3B8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (caActive) "Aggregating multiple frequency channels to increase bandwidth."
                            else "Single carrier link. Secondary component carriers inactive or standby.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        cell.activeCarriers.forEachIndexed { index, carrier ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Component Carrier Badge
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (carrier.type == "PCC") Color(0xFF10B981).copy(alpha = 0.15f)
                                            else Color(0xFF38BDF8).copy(alpha = 0.15f)
                                        )
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = carrier.type,
                                        color = if (carrier.type == "PCC") Color(0xFF10B981) else Color(0xFF38BDF8),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Band Details Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = carrier.band,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "ARFCN: ${carrier.arfcn}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF64748B)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Signal Strength Info & Bar
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.width(90.dp)
                                ) {
                                    Text(
                                        text = "${carrier.rsrp} dBm",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = getSignalStrengthColor(carrier.rsrp),
                                        fontWeight = FontWeight.Bold
                                    )
                                    
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    // Visual signal bar for this carrier
                                    val percent = ((carrier.rsrp + 140f) / 90f).coerceIn(0f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF334155))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(percent)
                                                .clip(CircleShape)
                                                .background(getSignalStrengthColor(carrier.rsrp))
                                        )
                                    }
                                }
                            }

                            if (index < cell.activeCarriers.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = Color(0xFF334155),
                                    thickness = 1.dp
                                )
                            }
                        }
                    }
                }
            }

            // Connection Sparkline
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Signal History (RSRP)",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Last 30m",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        RsfphistorySparkline(rsrpHistory)
                    }
                }
            }

            // Live Compass Card
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Cell Tower Compass",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pointing to estimated tower coordinate",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Bearing: ${String.format(Locale.US, "%.1f°", bearing)}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (distance != null) String.format(Locale.US, "Range: %.1f km away", distance / 1000f) else "Calculating bearing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                        
                        // Compass Icon Graphic
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
                            CompassGraphic(bearing = bearing, deviceHeading = deviceHeading)
                        }
                    }
                }
            }
        }

        // Snapshot Trigger Button
        Button(
            onClick = onSnapshotClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(imageVector = Icons.Default.Camera, contentDescription = "Snapshot")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save State Snapshot")
        }
    }

    // Metric Info Dialog
    if (activeMetricInfo != null) {
        AlertDialog(
            onDismissRequest = { activeMetricInfo = null },
            confirmButton = {
                TextButton(onClick = { activeMetricInfo = null }) {
                    Text("Got It")
                }
            },
            title = { Text(text = activeMetricInfo!!.title) },
            text = { Text(text = activeMetricInfo!!.desc) }
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    sub: String,
    color: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
                maxLines = 1
            )
        }
    }
}

@Composable
fun RsfphistorySparkline(history: List<Int>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        if (history.size < 2) return@Canvas
        val maxVal = -50f
        val minVal = -120f
        val range = maxVal - minVal

        val points = history.takeLast(30)
        val stepX = size.width / (points.size - 1)
        val path = Path()

        points.forEachIndexed { index, valDbm ->
            val x = index * stepX
            val ratio = (valDbm.toFloat() - minVal) / range
            val y = size.height - (ratio * size.height)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw line
        drawPath(
            path = path,
            color = Color(0xFF10B981),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw Gradient Filling underneath
        val fillPath = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF10B981).copy(alpha = 0.3f),
                    Color.Transparent
                )
            )
        )
    }
}

@Composable
fun CompassGraphic(bearing: Float, deviceHeading: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer Ring
        drawCircle(
            color = Color(0xFF334155),
            radius = radius - 4.dp.toPx(),
            style = Stroke(width = 2.dp.toPx())
        )

        // Cardinal Letters (North oriented relative to device)
        val textPaint = Paint().asFrameworkPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 10.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val directions = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        directions.forEach { (text, angle) ->
            val rotatedAngle = Math.toRadians((angle - deviceHeading).toDouble())
            val x = center.x + (radius - 12.dp.toPx()) * sin(rotatedAngle).toFloat()
            val y = center.y - (radius - 12.dp.toPx()) * cos(rotatedAngle).toFloat()
            drawContext.canvas.nativeCanvas.drawText(text, x, y + 4.dp.toPx(), textPaint)
        }

        // Target Tower Pointer Arrow
        val arrowAngle = Math.toRadians((bearing - deviceHeading).toDouble())
        val arrowLength = radius - 18.dp.toPx()
        val arrowTip = Offset(
            center.x + arrowLength * sin(arrowAngle).toFloat(),
            center.y - arrowLength * cos(arrowAngle).toFloat()
        )

        // Side wings of arrow
        val leftWingAngle = arrowAngle - Math.toRadians(150.0)
        val rightWingAngle = arrowAngle + Math.toRadians(150.0)
        val wingLength = 12.dp.toPx()

        val leftWing = Offset(
            arrowTip.x + wingLength * sin(leftWingAngle).toFloat(),
            arrowTip.y - wingLength * cos(leftWingAngle).toFloat()
        )
        val rightWing = Offset(
            arrowTip.x + wingLength * sin(rightWingAngle).toFloat(),
            arrowTip.y - wingLength * cos(rightWingAngle).toFloat()
        )

        // Draw connecting line to tower
        drawLine(
            color = Color(0xFF38BDF8),
            start = center,
            end = arrowTip,
            strokeWidth = 3.dp.toPx()
        )

        val arrowPath = Path().apply {
            moveTo(arrowTip.x, arrowTip.y)
            lineTo(leftWing.x, leftWing.y)
            lineTo(rightWing.x, rightWing.y)
            close()
        }

        drawPath(
            path = arrowPath,
            color = Color(0xFF38BDF8)
        )
    }
}

@Composable
fun AcquiringSignalState() {
    val infiniteTransition = rememberInfiniteTransition(label = "acquiringSignal")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "acquiringSignalAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.SignalCellularAlt,
            contentDescription = null,
            tint = Color(0xFF10B981).copy(alpha = pulseAlpha),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Acquiring Signal...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Waiting for the device to report a serving cell tower.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

data class MetricInfo(val title: String, val desc: String)

fun getSignalStrengthColor(rsrp: Int): Color {
    return when {
        rsrp >= -80 -> Color(0xFF4CAF50)  // Excellent - Green
        rsrp >= -95 -> Color(0xFF8BC34A)  // Good - Light Green
        rsrp >= -110 -> Color(0xFFFFB74D) // Fair - Orange
        else -> Color(0xFFE57373)         // Poor - Red
    }
}

@Composable
fun ProviderLogo(operatorName: String?, modifier: Modifier = Modifier) {
    val name = operatorName?.lowercase(Locale.ROOT) ?: "unknown"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF1E293B)),
        contentAlignment = Alignment.Center
    ) {
        when {
            name.contains("t-mobile") || name.contains("t-mob") || name.contains("telekom") || name.contains("magenta") -> {
                // T-Mobile Logo: Elegant Magenta Circle with bold white 'T' flanked by dots
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFFE20074)) // T-Mobile Magenta
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "· T ·",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            name.contains("verizon") || name.contains("vzw") -> {
                // Verizon Logo: Black background with red checkmark next to bold white V or check mark
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFF000000)) // Pure Black
                }
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "V",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "✓",
                            color = Color(0xFFCD040B), // Verizon Red
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
            name.contains("at&t") || name.contains("att") -> {
                // AT&T Logo: Globe with white latitudinal stripes
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(color = Color(0xFF00A6FF)) // AT&T Blue
                    
                    // Draw 3D globe latitude stripes
                    val stripeColor = Color.White.copy(alpha = 0.85f)
                    val strokeW = 2.dp.toPx()
                    
                    drawArc(
                        color = stripeColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(r * 0.2f, r * 0.4f),
                        size = size * 0.8f,
                        style = Stroke(width = strokeW)
                    )
                    
                    drawArc(
                        color = stripeColor,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(r * 0.2f, r * 0.2f),
                        size = size * 0.8f,
                        style = Stroke(width = strokeW)
                    )
                    
                    drawLine(
                        color = stripeColor,
                        start = Offset(r * 0.1f, r),
                        end = Offset(r * 1.9f, r),
                        strokeWidth = strokeW + 1f
                    )
                }
            }
            name.contains("vodafone") -> {
                // Vodafone Logo: Red Circle with White Speechmark
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFFE60000)) // Vodafone Red
                    
                    // Draw speechmark icon
                    val r = size.minDimension / 2f
                    drawCircle(
                        color = Color.White,
                        radius = r * 0.35f,
                        center = Offset(r, r * 0.9f)
                    )
                }
                // Overlaid white comma/speechmark detail
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "’",
                        color = Color(0xFFE60000),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        modifier = Modifier.offset(y = (-4).dp)
                    )
                }
            }
            name.contains("orange") -> {
                // Orange Logo: Square Orange with White "O"
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color(0xFFFF6600)) // Orange
                }
                Text(
                    text = "O",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            else -> {
                // Generic beautiful carrier chip logo
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF34D399), Color(0xFF059669)),
                            center = Offset(size.width / 2f, size.height / 2f)
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Default.CellTower,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

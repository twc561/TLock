package com.example.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CellLog
import com.example.data.CellRepository
import com.example.location.LocationTracker
import com.example.telephony.CellModel
import com.example.telephony.ITelephonyTracker
import com.example.telephony.TelephonyTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

class TowerMonitoringService : Service() {

    private val binder = LocalBinder()

    private lateinit var telephonyTracker: ITelephonyTracker
    private lateinit var locationTracker: LocationTracker
    private lateinit var repository: CellRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _currentCell = MutableStateFlow(CellModel())
    val currentCell: StateFlow<CellModel> = _currentCell.asStateFlow()

    private val _towerLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val towerLocation: StateFlow<Pair<Double, Double>?> = _towerLocation.asStateFlow()

    private val _resolvedAddress = MutableStateFlow("Locating serving tower...")
    val resolvedAddress: StateFlow<String> = _resolvedAddress.asStateFlow()

    private val _confidenceRange = MutableStateFlow(0)
    val confidenceRange: StateFlow<Int> = _confidenceRange.asStateFlow()

    val userLocation get() = locationTracker.userLocation
    val deviceHeading get() = locationTracker.deviceHeading

    private var iconStyle = "dBm" // "dBm", "band", "tech", "bars"
    private var alertRsearchBelow = -110 // Alert threshold for RSRP
    private var isLoggingPaused = false

    inner class LocalBinder : Binder() {
        fun getService(): TowerMonitoringService = this@TowerMonitoringService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(this, serviceScope)
        repository = CellRepository(database.cellDao(), this)
        telephonyTracker = TelephonyTracker(this)
        locationTracker = LocationTracker(this)

        val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
        telephonyTracker.setPollIntervalSeconds(prefs.getInt("poll_interval", 3))
        telephonyTracker.setGnbBitLength(prefs.getInt("gnb_bits", 24))

        createNotificationChannels()
        startForegroundService()

        // Start listening to cell updates using custom task scheduler & power optimizer
        serviceScope.launch {
            // A small delay ensures the service's foreground state is fully registered by AppOps
            delay(1000L)

            var previousCell: CellModel? = null

            while (isActive) {
                val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
                val batchingEnabled = prefs.getBoolean("batching_enabled", true)
                val intervalSeconds = prefs.getInt("batch_interval", 60)
                val pollInterval = prefs.getInt("poll_interval", 3)

                if (batchingEnabled) {
                    Log.d("TowerMonitoringService", "Scheduler: Starting batched polling active window")
                    // 1. Acquire WakeLock for the active duration (3 polls spaced by pollInterval, plus buffer)
                    val activeWindowMs = (3 * pollInterval * 1000L) + 5000L
                    acquireWakeLock(activeWindowMs)

                    // 2. Wake up sensors and telephony tracking
                    locationTracker.startLocationTracking()
                    telephonyTracker.startMonitoring()

                    // 3. Perform a batch of 3 polls to reduce continuous wake time
                    val batchLogs = mutableListOf<CellLog>()

                    for (i in 0 until 3) {
                        if (!isActive) break
                        delay(pollInterval * 1000L)

                        val cell = telephonyTracker.cellState.value
                        _currentCell.value = cell

                        if (cell.cellId > 0) {
                            val lookup = repository.findTowerLocation(
                                tech = cell.tech,
                                mcc = cell.mcc ?: "310",
                                mnc = cell.mnc ?: "260",
                                area = cell.tac,
                                cid = cell.cellId,
                                openCellIdApiKey = prefs.getString("opencellid_key", null)
                            )

                            val userLoc = locationTracker.userLocation.value
                            val resolvedAddress = lookup.address?.takeIf { it.isNotBlank() }
                                ?: "Unmapped cell tower location"

                            _towerLocation.value = if (lookup.lat != null && lookup.lon != null) {
                                Pair(lookup.lat, lookup.lon)
                            } else {
                                null
                            }
                            _resolvedAddress.value = resolvedAddress
                            _confidenceRange.value = lookup.range

                            if (userLoc != null && !isLoggingPaused) {
                                val logEntry = CellLog(
                                    tech = cell.tech,
                                    mcc = cell.mcc ?: "310",
                                    mnc = cell.mnc ?: "260",
                                    operatorName = cell.operatorName ?: "Carrier",
                                    cellId = cell.cellId,
                                    nodebId = cell.nodebId,
                                    sectorId = cell.sectorId,
                                    pci = cell.pci,
                                    tac = cell.tac,
                                    arfcn = cell.arfcn,
                                    band = cell.bandName,
                                    rsrp = cell.rsrp,
                                    rsrq = cell.rsrq,
                                    sinr = cell.sinr,
                                    lat = userLoc.latitude,
                                    lon = userLoc.longitude,
                                    towerLat = lookup.lat,
                                    towerLon = lookup.lon,
                                    address = resolvedAddress,
                                    source = lookup.source
                                )
                                batchLogs.add(logEntry)
                            }

                            // Update widget
                            com.example.widget.TowerLockWidget.sendWidgetUpdate(this@TowerMonitoringService, cell, resolvedAddress)

                            // Alert rules engine
                            previousCell?.let { prev ->
                                triggerAlertsEngine(prev, cell, lookup.source)
                            }
                            previousCell = cell

                            updateNotification(cell)
                        }
                    }

                    // 4. Batch insert all recorded logs in a single SQLite transaction
                    if (batchLogs.isNotEmpty() && !isLoggingPaused) {
                        Log.d("TowerMonitoringService", "Scheduler: Saving batch of ${batchLogs.size} logs to database")
                        repository.insertLogs(batchLogs)
                    }

                    // 5. Completely sleep sensors and telephony tracking to conserve battery
                    Log.d("TowerMonitoringService", "Scheduler: Suspending trackers for background sleep")
                    locationTracker.stopLocationTracking()
                    telephonyTracker.stopMonitoring()

                    // 6. Release active WakeLock
                    releaseWakeLock()

                    // 7. Standby sleep for the remaining batch interval
                    val sleepSeconds = (intervalSeconds - (3 * pollInterval)).coerceAtLeast(10)
                    Log.d("TowerMonitoringService", "Scheduler: Power-save sleep for $sleepSeconds seconds")
                    delay(sleepSeconds * 1000L)

                } else {
                    // Continuous Real-Time Tracking mode
                    Log.d("TowerMonitoringService", "Scheduler: Running in real-time continuous mode")
                    locationTracker.startLocationTracking()
                    telephonyTracker.startMonitoring()

                    val collectJob = launch {
                        telephonyTracker.cellState.collect { cell ->
                            _currentCell.value = cell

                            if (!isLoggingPaused && cell.cellId > 0) {
                                val lookup = repository.findTowerLocation(
                                    tech = cell.tech,
                                    mcc = cell.mcc ?: "310",
                                    mnc = cell.mnc ?: "260",
                                    area = cell.tac,
                                    cid = cell.cellId,
                                    openCellIdApiKey = prefs.getString("opencellid_key", null)
                                )

                                val userLoc = locationTracker.userLocation.value
                                val resolvedAddress = lookup.address?.takeIf { it.isNotBlank() }
                                    ?: "Unmapped cell tower location"

                                _towerLocation.value = if (lookup.lat != null && lookup.lon != null) {
                                    Pair(lookup.lat, lookup.lon)
                                } else {
                                    null
                                }
                                _resolvedAddress.value = resolvedAddress
                                _confidenceRange.value = lookup.range

                                if (userLoc != null) {
                                    val logEntry = CellLog(
                                        tech = cell.tech,
                                        mcc = cell.mcc ?: "310",
                                        mnc = cell.mnc ?: "260",
                                        operatorName = cell.operatorName ?: "Carrier",
                                        cellId = cell.cellId,
                                        nodebId = cell.nodebId,
                                        sectorId = cell.sectorId,
                                        pci = cell.pci,
                                        tac = cell.tac,
                                        arfcn = cell.arfcn,
                                        band = cell.bandName,
                                        rsrp = cell.rsrp,
                                        rsrq = cell.rsrq,
                                        sinr = cell.sinr,
                                        lat = userLoc.latitude,
                                        lon = userLoc.longitude,
                                        towerLat = lookup.lat,
                                        towerLon = lookup.lon,
                                        address = resolvedAddress,
                                        source = lookup.source
                                    )
                                    repository.insertLog(logEntry)
                                }

                                com.example.widget.TowerLockWidget.sendWidgetUpdate(this@TowerMonitoringService, cell, resolvedAddress)

                                previousCell?.let { prev ->
                                    triggerAlertsEngine(prev, cell, lookup.source)
                                }
                                previousCell = cell
                            }

                            updateNotification(cell)
                        }
                    }

                    // Dynamically detect configuration shifts back to batching
                    while (isActive) {
                        val currentBatchingEnabled = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("batching_enabled", true)
                        if (currentBatchingEnabled) {
                            Log.d("TowerMonitoringService", "Scheduler: Transitioning back to batched mode")
                            break
                        }
                        delay(2000L)
                    }
                    collectJob.cancel()
                }
            }
        }
    }

    private fun triggerAlertsEngine(prev: CellModel, current: CellModel, source: String) {
        // 1. Notify on band change
        if (prev.bandName != current.bandName && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_band", false)) {
            triggerAlert("Band Changed", "Moved from ${prev.bandName} to ${current.bandName}")
        }
        // 2. SA <-> NSA transition
        if (prev.tech != current.tech && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_tech", false)) {
            triggerAlert("Technology Shift", "Connection shifted from ${prev.tech} to ${current.tech}")
        }
        // 3. RSRP below threshold
        if (current.rsrp < alertRsearchBelow && prev.rsrp >= alertRsearchBelow && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_rsrp", false)) {
            triggerAlert("Weak Signal Alert", "RSRP dropped below threshold to ${current.rsrp} dBm")
        }
        // 4. Connecting to an unmapped tower
        if (source == "Unmapped cell" && getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_unmapped", false)) {
            triggerAlert("Unmapped Tower Detected", "Connected to cell ${current.cellId} which is not mapped.")
        }
        // 5. 5G SA to LTE Drop
        val isPrev5gSa = prev.tech == "5G SA"
        val isCurrentLte = current.tech == "4G LTE" || current.tech == "LTE" || current.tech.contains("LTE")
        if (isPrev5gSa && isCurrentLte) {
            val prefs = getSharedPreferences("TowerLockPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("alert_5g_drop", true)) {
                val userLoc = locationTracker.userLocation.value
                val locationLabel = if (userLoc != null) {
                    String.format(
                        java.util.Locale.US,
                        "(%.5f, %.5f)",
                        userLoc.latitude,
                        userLoc.longitude
                    )
                } else {
                    "location unavailable"
                }
                triggerAlert("5G Connection Dropped", "Device switched from 5G SA to legacy LTE at: $locationLabel")

                if (prefs.getBoolean("log_drop_coords", true) && userLoc != null) {
                    serviceScope.launch {
                        val dropLog = CellLog(
                            tech = "5G SA to LTE Drop",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = null,
                            towerLon = null,
                            address = "Recorded 5G SA to LTE drop coordinates",
                            source = "5G Drop Monitor"
                        )
                        repository.insertLog(dropLog)
                    }
                }
            }
        }
    }

    private fun triggerAlert(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, "Alerts")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (getSharedPreferences("TowerLockPrefs", MODE_PRIVATE).getBoolean("alert_vibrate", true)) {
            builder.setVibrate(longArrayOf(0, 300, 100, 300))
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun startForegroundService() {
        val notification = createNotification(CellModel())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitoringChannel = NotificationChannel(
                "Monitoring",
                "Tower Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent display of current cellular serving cell stats"
                enableLights(false)
                enableVibration(false)
            }

            val alertsChannel = NotificationChannel(
                "Alerts",
                "Status Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires on cell tower handovers, weak signals, and unmapped cells"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitoringChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun getBandSummary(cell: CellModel): String {
        if (cell.activeCarriers.isEmpty()) {
            return cell.bandName.ifBlank { "Unknown" }
        }
        val bands = cell.activeCarriers.map { it.band.trim() }.filter { it.isNotBlank() }
        val joinedBands = if (bands.isNotEmpty()) {
            bands.joinToString("+")
        } else {
            cell.bandName
        }
        val caText = if (cell.activeCarriers.size > 1) " (${cell.activeCarriers.size}CC CA)" else ""
        return "$joinedBands$caText"
    }

    private fun createNotification(cell: CellModel): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Actions
        val pauseIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_PAUSE"
        }
        val pausePendingIntent = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val snapshotIntent = Intent(this, TowerMonitoringService::class.java).apply {
            action = "ACTION_SNAPSHOT"
        }
        val snapshotPendingIntent = PendingIntent.getService(this, 2, snapshotIntent, PendingIntent.FLAG_IMMUTABLE)

        val bandSummary = getBandSummary(cell)
        val textSummary = "${cell.tech} • $bandSummary • ${cell.rsrp} dBm"

        val gnbLabel = if (cell.tech.contains("5G")) "gNB" else "eNB"
        val towerNodeb = if (cell.nodebId > 0) "${cell.nodebId}" else "Unknown"
        val sectorText = if (cell.sectorId >= 0) "Sector ${cell.sectorId}" else "Sector N/A"

        val addressText = _resolvedAddress.value.ifBlank { "Resolving location..." }

        val caSummary = if (cell.activeCarriers.size > 1) {
            val carriersJoined = cell.activeCarriers.joinToString(" + ") { carrier ->
                "<b>${carrier.band}</b> (${carrier.rsrp} dBm)"
            }
            "Active (${cell.activeCarriers.size}CC): $carriersJoined"
        } else {
            "Standby"
        }

        val htmlContent = """
            <b>📍 Tower:</b> $addressText<br/>
            <b>📶 RF Link:</b> ${cell.tech} • <b>$bandSummary</b> • <b>${cell.frequencyMhz} MHz</b><br/>
            <b>⚡ Signal:</b> <b>${cell.rsrp} dBm</b> (SINR: <b>${cell.sinr} dB</b>) • ${cell.signalGrade}<br/>
            <b>🆔 Cell ID:</b> $gnbLabel <b>$towerNodeb</b> • $sectorText • PCI <b>${cell.pci}</b> • TAC <b>${cell.tac}</b><br/>
            <b>🔗 Carrier Aggregation:</b> $caSummary
        """.trimIndent()

        val spannedBigText = HtmlCompat.fromHtml(
            htmlContent,
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val spannedTitle = HtmlCompat.fromHtml(
            "TowerLock • <b>${cell.operatorName ?: "Cell Monitor"}</b>",
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val iconText = when (iconStyle) {
            "dBm" -> "${cell.rsrp}"
            "band" -> cell.bandName.substringBefore(" ").replace("n", "").replace("B", "")
            "tech" -> if (cell.tech.contains("5G")) "5G" else "4G"
            else -> "Bars"
        }

        val dynamicIcon = createDynamicIcon(iconText)

        val builder = NotificationCompat.Builder(this, "Monitoring")
            .setContentTitle(spannedTitle)
            .setContentText(textSummary)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spannedBigText))
            .addAction(R.drawable.ic_launcher_foreground, if (isLoggingPaused) "Resume" else "Pause Logging", pausePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Snapshot", snapshotPendingIntent)

        builder.setSmallIcon(dynamicIcon)

        return builder.build()
    }

    private fun updateNotification(cell: CellModel) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, createNotification(cell))
    }

    private fun createDynamicIcon(text: String): androidx.core.graphics.drawable.IconCompat {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background Circle for visual depth
        val bgPaint = Paint().apply {
            color = if (text.startsWith("-") || text.all { it.isDigit() }) {
                val num = text.toIntOrNull() ?: -100
                when {
                    num >= -80 -> 0xFF4CAF50.toInt()
                    num >= -95 -> 0xFF8BC34A.toInt()
                    num >= -110 -> 0xFFFFB74D.toInt()
                    else -> 0xFFE57373.toInt()
                }
            } else {
                0xFF2196F3.toInt()
            }
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = if (text.length > 3) 20f else 26f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        val xPos = canvas.width / 2f
        val yPos = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(text, xPos, yPos, paint)

        return androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PAUSE" -> {
                isLoggingPaused = !isLoggingPaused
                updateNotification(_currentCell.value)
            }
            "ACTION_SNAPSHOT" -> {
                serviceScope.launch {
                    val current = _currentCell.value
                    val userLoc = locationTracker.userLocation.value
                    if (current.cellId > 0 && userLoc != null) {
                        val snapshotLog = CellLog(
                            tech = "${current.tech} (SNAPSHOT)",
                            mcc = current.mcc ?: "310",
                            mnc = current.mnc ?: "260",
                            operatorName = current.operatorName ?: "Carrier",
                            cellId = current.cellId,
                            nodebId = current.nodebId,
                            sectorId = current.sectorId,
                            pci = current.pci,
                            tac = current.tac,
                            arfcn = current.arfcn,
                            band = current.bandName,
                            rsrp = current.rsrp,
                            rsrq = current.rsrq,
                            sinr = current.sinr,
                            lat = userLoc.latitude,
                            lon = userLoc.longitude,
                            towerLat = _towerLocation.value?.first,
                            towerLon = _towerLocation.value?.second,
                            address = "Manual snapshot saved by user",
                            source = "Manual Snapshot"
                        )
                        repository.insertLog(snapshotLog)
                        triggerAlert("Snapshot Saved", "Serving cell state recorded in log history.")
                    } else if (current.cellId > 0) {
                        triggerAlert("Snapshot Failed", "GPS location not yet available; try again shortly.")
                    }
                }
            }
            "UPDATE_ICON_STYLE" -> {
                iconStyle = intent.getStringExtra("STYLE") ?: "dBm"
                updateNotification(_currentCell.value)
            }
            "UPDATE_ALERT_THRESHOLDS" -> {
                alertRsearchBelow = intent.getIntExtra("RSRP_THRESHOLD", -110)
            }
            "UPDATE_POLL_INTERVAL" -> {
                telephonyTracker.setPollIntervalSeconds(intent.getIntExtra("POLL_INTERVAL", 3))
            }
            "UPDATE_GNB_BITS" -> {
                telephonyTracker.setGnbBitLength(intent.getIntExtra("GNB_BITS", 24))
            }
        }
        return START_STICKY
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "TowerLockPro:MonitoringWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeoutMs)
                Log.d("TowerMonitoringService", "WakeLock acquired for $timeoutMs ms")
            }
        } catch (e: Exception) {
            Log.e("TowerMonitoringService", "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("TowerMonitoringService", "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e("TowerMonitoringService", "Failed to release WakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationTracker.stopLocationTracking()
        telephonyTracker.stopMonitoring()
        releaseWakeLock()
        serviceScope.cancel()
    }
}

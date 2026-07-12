package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.CellRepository
import com.example.data.TowerDbEntry
import com.example.service.TowerMonitoringService
import com.example.telephony.CellModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.MapScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var monitoringService: TowerMonitoringService? = null
    private var isBound = false
    private val serviceJobs = mutableListOf<Job>()

    private fun cancelServiceJobs() {
        serviceJobs.forEach { it.cancel() }
        serviceJobs.clear()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TowerMonitoringService.LocalBinder
            monitoringService = binder.getService()
            isBound = true
            
            cancelServiceJobs()
            // Connect flow
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.currentCell?.collectLatest { cell ->
                    currentCellState.value = cell

                    // Add RSRP reading to history
                    if (cell.rsrp != -140) {
                        val currentList = rsrpHistoryState.value.toMutableList()
                        currentList.add(cell.rsrp)
                        if (currentList.size > 30) currentList.removeAt(0)
                        rsrpHistoryState.value = currentList
                    }
                }
            })
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.userLocation?.collectLatest { location ->
                    userLocationState.value = location?.let { it.latitude to it.longitude }
                }
            })
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.towerLocation?.collectLatest { towerLocationState.value = it }
            })
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.resolvedAddress?.collectLatest { resolvedAddressState.value = it }
            })
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.confidenceRange?.collectLatest { confidenceRangeState.value = it }
            })
            serviceJobs.add(lifecycleScope.launch {
                monitoringService?.deviceHeading?.collectLatest { deviceHeadingState.value = it }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitoringService = null
            isBound = false
            cancelServiceJobs()
        }
    }

    private val currentCellState = mutableStateOf(CellModel())
    private val rsrpHistoryState = mutableStateOf<List<Int>>(emptyList())
    private val userLocationState = mutableStateOf<Pair<Double, Double>?>(null)
    private val towerLocationState = mutableStateOf<Pair<Double, Double>?>(null)
    private val resolvedAddressState = mutableStateOf("Locating serving tower...")
    private val confidenceRangeState = mutableStateOf(0)
    private val deviceHeadingState = mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(this, lifecycleScope)
        val repository = CellRepository(db.cellDao(), this)

        setContent {
            MyApplicationTheme {
                MainAppScreen(
                    repository = repository,
                    currentCell = currentCellState.value,
                    rsrpHistory = rsrpHistoryState.value,
                    userLocation = userLocationState.value,
                    towerLocation = towerLocationState.value,
                    resolvedAddress = resolvedAddressState.value,
                    confidenceRange = confidenceRangeState.value,
                    deviceHeading = deviceHeadingState.value,
                    onStartService = { startAndBindService() },
                    onStopService = { stopAndUnbindService() },
                    onBackupDb = { backupDatabase() },
                    onRestoreDb = { restoreDatabase() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Auto-start and bind the monitoring service if permissions are already granted
        if (hasRequiredPermissions() && !isBound) {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, TowerMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopAndUnbindService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, TowerMonitoringService::class.java))
    }

    private fun hasRequiredPermissions(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        return coarse == PackageManager.PERMISSION_GRANTED &&
                fine == PackageManager.PERMISSION_GRANTED &&
                phone == PackageManager.PERMISSION_GRANTED
    }

    private fun backupDatabase() {
        try {
            val dbFile = getDatabasePath("towerlock_database")
            val backupFile = File(getExternalFilesDir(null), "towerlock_database.bak")
            
            if (dbFile.exists()) {
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(backupFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, "Backup saved to: ${backupFile.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Database not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreDatabase() {
        try {
            val dbFile = getDatabasePath("towerlock_database")
            val backupFile = File(getExternalFilesDir(null), "towerlock_database.bak")
            
            if (backupFile.exists()) {
                FileInputStream(backupFile).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, "Restore successful! Restarting monitor...", Toast.LENGTH_LONG).show()
                stopAndUnbindService()
                startAndBindService()
            } else {
                Toast.makeText(this, "Backup file not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    repository: CellRepository,
    currentCell: CellModel,
    rsrpHistory: List<Int>,
    userLocation: Pair<Double, Double>?,
    towerLocation: Pair<Double, Double>?,
    resolvedAddress: String,
    confidenceRange: Int,
    deviceHeading: Float,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onBackupDb: () -> Unit,
    onRestoreDb: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var hasPermissions by remember { mutableStateOf(hasAllRequiredPermissions(context)) }
    var isMonitoringActive by remember { mutableStateOf(hasPermissions) }

    val logs by repository.allLogs.collectAsState(initial = emptyList())
    val towers by repository.allTowers.collectAsState(initial = emptyList())

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        hasPermissions = granted
        if (granted) {
            isMonitoringActive = true
            onStartService()
        } else {
            Toast.makeText(context, "Permissions required for monitoring", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            if (hasPermissions) {
                TowerLockTopBar(
                    isMonitoring = isMonitoringActive,
                    onToggleMonitoring = {
                        if (isMonitoringActive) {
                            onStopService()
                        } else {
                            onStartService()
                        }
                        isMonitoringActive = !isMonitoringActive
                    }
                )
            }
        },
        bottomBar = {
            if (hasPermissions) {
                NavigationBar(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(imageVector = Icons.Default.Dashboard, contentDescription = "Dashboard") },
                        label = { Text("Status") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            unselectedIconColor = Color.Gray,
                            selectedTextColor = Color(0xFF10B981)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(imageVector = Icons.Default.Map, contentDescription = "Map") },
                        label = { Text("Map") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            unselectedIconColor = Color.Gray,
                            selectedTextColor = Color(0xFF10B981)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(imageVector = Icons.Default.History, contentDescription = "Logs") },
                        label = { Text("Logs") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            unselectedIconColor = Color.Gray,
                            selectedTextColor = Color(0xFF10B981)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            unselectedIconColor = Color.Gray,
                            selectedTextColor = Color(0xFF10B981)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermissions) {
                PermissionRationaleView(onRequestPermissions = {
                    val permissionsNeeded = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(permissionsNeeded.toTypedArray())
                })
            } else {
                when (selectedTab) {
                    0 -> DashboardScreen(
                        cell = currentCell,
                        userLat = userLocation?.first,
                        userLon = userLocation?.second,
                        towerLat = towerLocation?.first,
                        towerLon = towerLocation?.second,
                        resolvedAddress = resolvedAddress,
                        confidenceMeters = confidenceRange,
                        deviceHeading = deviceHeading,
                        rsrpHistory = rsrpHistory,
                        onSnapshotClick = {
                            val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                action = "ACTION_SNAPSHOT"
                            }
                            context.startService(intent)
                        }
                    )
                    1 -> {
                        val coroutineScope = rememberCoroutineScope()
                        MapScreen(
                            cell = currentCell,
                            userLat = userLocation?.first,
                            userLon = userLocation?.second,
                            towerLat = towerLocation?.first,
                            towerLon = towerLocation?.second,
                            towerAddress = resolvedAddress,
                            confidenceMeters = confidenceRange,
                            allTowers = towers,
                            onSaveTower = { lat, lon, address ->
                                coroutineScope.launch {
                                    repository.insertCustomTower(
                                        TowerDbEntry(
                                            radio = if (currentCell.tech.contains("5G")) "NR" else "LTE",
                                            mcc = currentCell.mcc ?: "310",
                                            mnc = currentCell.mnc ?: "260",
                                            area = currentCell.tac,
                                            cid = currentCell.cellId,
                                            lat = lat,
                                            lon = lon,
                                            range = 50,
                                            address = address
                                        )
                                    )
                                    Toast.makeText(context, "Tower location saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    2 -> {
                        val coroutineScope = rememberCoroutineScope()
                        LogsScreen(
                            logs = logs,
                            onDeleteLog = { log ->
                                coroutineScope.launch { repository.deleteLog(log) }
                            },
                            onClearAllLogs = {
                                coroutineScope.launch { repository.clearAllLogs() }
                            }
                        )
                    }
                    3 -> {
                        val coroutineScope = rememberCoroutineScope()
                        SettingsScreen(
                            onSaveApiKey = { key ->
                                context.getSharedPreferences("TowerLockPrefs", Context.MODE_PRIVATE)
                                    .edit().putString("opencellid_key", key).apply()
                            },
                            onSavePollInterval = { sec ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_POLL_INTERVAL"
                                    putExtra("POLL_INTERVAL", sec)
                                }
                                context.startService(intent)
                            },
                            onSaveGnbBitLength = { bits ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_GNB_BITS"
                                    putExtra("GNB_BITS", bits)
                                }
                                context.startService(intent)
                            },
                            onSaveIconStyle = { style ->
                                val intent = Intent(context, TowerMonitoringService::class.java).apply {
                                    action = "UPDATE_ICON_STYLE"
                                    putExtra("STYLE", style)
                                }
                                context.startService(intent)
                            },
                            onBackupDb = onBackupDb,
                            onRestoreDb = onRestoreDb,
                            onImportCsv = { uri ->
                                coroutineScope.launch {
                                    val imported = context.contentResolver.openInputStream(uri)?.use { stream ->
                                        repository.importCsv(stream)
                                    } ?: 0
                                    Toast.makeText(context, "Imported $imported towers", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerLockTopBar(isMonitoring: Boolean, onToggleMonitoring: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SignalCellularAlt,
                    contentDescription = null,
                    tint = com.example.ui.theme.TlEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("TowerLock", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        actions = {
            LiveStatusIndicator(isMonitoring = isMonitoring)
            IconButton(onClick = onToggleMonitoring) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = if (isMonitoring) "Stop monitoring" else "Start monitoring",
                    tint = if (isMonitoring) com.example.ui.theme.TlTextSecondary else com.example.ui.theme.TlEmerald
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = com.example.ui.theme.TlSurface,
            titleContentColor = Color.White
        )
    )
}

@Composable
fun LiveStatusIndicator(isMonitoring: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isMonitoring) {
                        com.example.ui.theme.TlEmerald.copy(alpha = dotAlpha)
                    } else {
                        com.example.ui.theme.TlTextMuted
                    }
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isMonitoring) "LIVE" else "PAUSED",
            color = if (isMonitoring) com.example.ui.theme.TlEmerald else com.example.ui.theme.TlTextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PermissionRationaleView(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Security Permissions",
            tint = Color(0xFF10B981),
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Fine Location & Telephony Access",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "TowerLock Pro requires Fine Location and Read Phone State permissions to identify your active cellular serving base stations, calculate Timing Advance distances, and geocode nearby tower nodes.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Required Permissions", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun hasAllRequiredPermissions(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
    return fine == PackageManager.PERMISSION_GRANTED && phone == PackageManager.PERMISSION_GRANTED
}

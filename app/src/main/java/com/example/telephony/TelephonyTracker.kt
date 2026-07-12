package com.example.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import kotlin.random.Random

class TelephonyTracker(
    private val context: Context
) : ITelephonyTracker {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _cellState = MutableStateFlow(CellModel())
    override val cellState: StateFlow<CellModel> = _cellState.asStateFlow()

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private var pollIntervalSeconds = 3
    private var gnbBitLength = 24 // default 24-bit for T-Mobile 310-260

    // Callback objects for API 31+
    private var telephonyCallback: Any? = null

    // Simulation fields
    private var isSimulationMode = true
    private var simMcc = "310"
    private var simMnc = "260"
    private var simOperator = "T-Mobile"
    private var simSectorIndex = 1
    private var simRsfphistory = mutableListOf<Int>()

    init {
        // Automatically detect if running in emulator or has no active SIM card
        isSimulationMode = checkIfSimulationRequired()
        if (isSimulationMode) {
            setupMockCellState()
        }
    }

    private fun checkIfSimulationRequired(): Boolean {
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        ) {
            return true
        }
        // Also check if telephonyManager has no SIM card or service
        return telephonyManager.simState != TelephonyManager.SIM_STATE_READY
    }

    override fun setPollIntervalSeconds(seconds: Int) {
        pollIntervalSeconds = seconds.coerceIn(1, 10)
        Log.d("TelephonyTracker", "Poll interval updated to $pollIntervalSeconds s")
    }

    override fun setGnbBitLength(bits: Int) {
        gnbBitLength = bits.coerceIn(20, 28)
        Log.d("TelephonyTracker", "gNB bit length updated to $gnbBitLength bits")
    }

    override fun updateSimulatedCell(mockCell: CellModel) {
        _cellState.value = mockCell
    }

    override fun startMonitoring() {
        stopMonitoring()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        isSimulationMode = checkIfSimulationRequired()

        // 1. Setup real listening if permissions are granted and not in absolute simulation mode
        if (!isSimulationMode && hasPermissions()) {
            registerRealListeners()
        }

        // 2. Start polling loop
        job = scope.launch {
            while (isActive) {
                if (isSimulationMode) {
                    runSimulationStep()
                } else {
                    pollRealTelephony()
                }
                delay(pollIntervalSeconds * 1000L)
            }
        }
    }

    override fun stopMonitoring() {
        job?.cancel()
        job = null
        unregisterRealListeners()
        scope.cancel()
    }

    private fun hasPermissions(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        return coarse == PackageManager.PERMISSION_GRANTED &&
                fine == PackageManager.PERMISSION_GRANTED &&
                phone == PackageManager.PERMISSION_GRANTED
    }

    private fun registerRealListeners() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(),
                    TelephonyCallback.CellInfoListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.SignalStrengthsListener {

                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        parseCellInfoList(cellInfo)
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        updateDisplayInfo(telephonyDisplayInfo)
                    }

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        updateSignalStrength(signalStrength)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            }
        } catch (e: SecurityException) {
            Log.e("TelephonyTracker", "Permission denied for telephony callback", e)
        } catch (e: Exception) {
            Log.e("TelephonyTracker", "Error registering telephony callback", e)
        }
    }

    private fun unregisterRealListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
                } catch (e: Exception) {
                    Log.e("TelephonyTracker", "Error unregistering callback", e)
                }
            }
            telephonyCallback = null
        }
    }

    private fun pollRealTelephony() {
        if (!hasPermissions()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                telephonyManager.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfoList: List<CellInfo>) {
                        if (!cellInfoList.isNullOrEmpty()) {
                            parseCellInfoList(cellInfoList)
                        } else {
                            if (checkIfSimulationRequired()) {
                                isSimulationMode = true
                            }
                        }
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                val cellInfoList = telephonyManager.allCellInfo
                if (cellInfoList.isNullOrEmpty()) {
                    if (checkIfSimulationRequired()) {
                        isSimulationMode = true
                    }
                    return
                }
                parseCellInfoList(cellInfoList)
            }
        } catch (e: SecurityException) {
            Log.e("TelephonyTracker", "Permission error polling cell info", e)
        } catch (e: Exception) {
            Log.e("TelephonyTracker", "Error polling cell info", e)
        }
    }

    private fun parseCellInfoList(cellInfoList: List<CellInfo>) {
        if (cellInfoList.isEmpty()) return
        
        // Find registered/serving cell
        val servingCell = cellInfoList.find { it.isRegistered } ?: cellInfoList.first()
        val neighborsList = cellInfoList.filter { !it.isRegistered }

        val builder = CellModelBuilder()
        builder.operatorName = telephonyManager.networkOperatorName.ifBlank { "Carrier" }

        // Decode technology and identity
        when (servingCell) {
            is CellInfoLte -> {
                val id = servingCell.cellIdentity
                val sig = servingCell.cellSignalStrength

                builder.tech = "4G LTE"
                builder.mcc = id.mccString ?: "310"
                builder.mnc = id.mncString ?: "260"
                builder.tac = sanitizeInt(id.tac)
                builder.cellId = sanitizeLong(id.ci.toLong())
                builder.pci = sanitizeInt(id.pci)
                builder.arfcn = sanitizeInt(id.earfcn)

                // Decode eNB and sector for LTE (28-bit Cell Identity)
                if (builder.cellId > 0 && builder.cellId != 2147483647L) {
                    builder.nodebId = builder.cellId shr 8
                    builder.sectorId = (builder.cellId and 0xFF).toInt()
                }

                builder.rsrp = sanitizeSignal(sig.rsrp)
                builder.rsrq = sanitizeSignal(sig.rsrq)
                builder.sinr = sanitizeSignal(sig.rssnr) // for LTE, rssnr serves as SINR
                builder.timingAdvance = sanitizeInt(sig.timingAdvance)

                val bandData = BandFrequencyMapper.decodeLteEarfcn(builder.arfcn)
                builder.bandName = bandData.first
                builder.frequencyMhz = bandData.second
                builder.bandwidthKhz = sanitizeInt(id.bandwidth)
            }
            is CellInfoNr -> {
                val id = servingCell.cellIdentity as CellIdentityNr
                val sig = servingCell.cellSignalStrength as CellSignalStrengthNr

                builder.tech = "5G SA"
                builder.mcc = id.mccString ?: "310"
                builder.mnc = id.mncString ?: "260"
                builder.tac = sanitizeInt(id.tac)
                builder.cellId = sanitizeLong(id.nci)
                builder.pci = sanitizeInt(id.pci)
                builder.arfcn = sanitizeInt(id.nrarfcn)

                // Decode gNB and sector for 5G NR (36-bit Cell Identity)
                if (builder.cellId > 0 && builder.cellId != 9223372036854775807L) {
                    val shiftBits = 36 - gnbBitLength
                    builder.nodebId = builder.cellId shr shiftBits
                    builder.sectorId = (builder.cellId and ((1L shl shiftBits) - 1)).toInt()
                }

                // Parse 5G NR Signal Strengths
                builder.ssRsrp = sanitizeSignal(sig.ssRsrp)
                builder.ssRsrq = sanitizeSignal(sig.ssRsrq)
                builder.ssSinr = sanitizeSignal(sig.ssSinr)
                builder.csiRsrp = sanitizeSignal(sig.csiRsrp)
                builder.csiRsrq = sanitizeSignal(sig.csiRsrq)
                builder.csiSinr = sanitizeSignal(sig.csiSinr)

                // Use SS-RSRP as primary metrics
                builder.rsrp = builder.ssRsrp
                builder.rsrq = builder.ssRsrq
                builder.sinr = builder.ssSinr

                // 5G Timing Advance if available (added in SDK 31)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Nr we can query timing advance if available, else 0
                    builder.timingAdvance = 0
                }

                val bandData = BandFrequencyMapper.decodeNrArfcn(builder.arfcn)
                builder.bandName = bandData.first
                builder.frequencyMhz = bandData.second
            }
        }

        // Map neighbor cell table
        val neighbors = neighborsList.mapNotNull { cell ->
            when (cell) {
                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val sig = cell.cellSignalStrength
                    val bandData = BandFrequencyMapper.decodeLteEarfcn(id.earfcn)
                    NeighborCell(
                        pci = sanitizeInt(id.pci),
                        band = bandData.first,
                        arfcn = sanitizeInt(id.earfcn),
                        rsrp = sanitizeSignal(sig.rsrp),
                        tech = "LTE"
                    )
                }
                is CellInfoNr -> {
                    val id = cell.cellIdentity as CellIdentityNr
                    val sig = cell.cellSignalStrength as CellSignalStrengthNr
                    val bandData = BandFrequencyMapper.decodeNrArfcn(id.nrarfcn)
                    NeighborCell(
                        pci = sanitizeInt(id.pci),
                        band = bandData.first,
                        arfcn = sanitizeInt(id.nrarfcn),
                        rsrp = sanitizeSignal(sig.ssRsrp),
                        tech = "NR"
                    )
                }
                else -> null
            }
        }
        builder.neighbors = neighbors

        // Extract active carriers for Carrier Aggregation
        val activeCarriersList = mutableListOf<CarrierInfo>()
        val servingBandData = when (servingCell) {
            is CellInfoLte -> BandFrequencyMapper.decodeLteEarfcn(servingCell.cellIdentity.earfcn)
            is CellInfoNr -> BandFrequencyMapper.decodeNrArfcn((servingCell.cellIdentity as CellIdentityNr).nrarfcn)
            else -> Pair("Unknown", 0.0)
        }
        val servingArfcn = when (servingCell) {
            is CellInfoLte -> servingCell.cellIdentity.earfcn
            is CellInfoNr -> (servingCell.cellIdentity as CellIdentityNr).nrarfcn
            else -> 0
        }
        val servingRsrp = when (servingCell) {
            is CellInfoLte -> servingCell.cellSignalStrength.rsrp
            is CellInfoNr -> (servingCell.cellSignalStrength as CellSignalStrengthNr).ssRsrp
            else -> -140
        }
        activeCarriersList.add(
            CarrierInfo(
                band = servingBandData.first,
                arfcn = sanitizeInt(servingArfcn),
                rsrp = sanitizeSignal(servingRsrp),
                type = "PCC"
            )
        )

        // Capture secondary registered cells as SCCs if reported
        cellInfoList.filter { it.isRegistered && it != servingCell }.forEach { cell ->
            val bandData = when (cell) {
                is CellInfoLte -> BandFrequencyMapper.decodeLteEarfcn(cell.cellIdentity.earfcn)
                is CellInfoNr -> BandFrequencyMapper.decodeNrArfcn((cell.cellIdentity as CellIdentityNr).nrarfcn)
                else -> null
            }
            if (bandData != null) {
                val arfcn = when (cell) {
                    is CellInfoLte -> cell.cellIdentity.earfcn
                    is CellInfoNr -> (cell.cellIdentity as CellIdentityNr).nrarfcn
                    else -> 0
                }
                val rsrp = when (cell) {
                    is CellInfoLte -> cell.cellSignalStrength.rsrp
                    is CellInfoNr -> (cell.cellSignalStrength as CellSignalStrengthNr).ssRsrp
                    else -> -140
                }
                activeCarriersList.add(
                    CarrierInfo(
                        band = bandData.first,
                        arfcn = sanitizeInt(arfcn),
                        rsrp = sanitizeSignal(rsrp),
                        type = "SCC"
                    )
                )
            }
        }
        builder.activeCarriers = activeCarriersList

        // Update current state
        val updatedCell = builder.build()
        _cellState.value = updatedCell
    }

    private fun updateDisplayInfo(displayInfo: TelephonyDisplayInfo) {
        val current = _cellState.value
        val techOverride = when (displayInfo.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G NSA (Adv)"
            else -> current.tech
        }
        if (techOverride != current.tech) {
            _cellState.value = current.copy(tech = techOverride)
        }
    }

    private fun updateSignalStrength(signalStrength: SignalStrength) {
        // Fallback or supplementary signal metrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cellSignalStrengths = signalStrength.cellSignalStrengths
            if (cellSignalStrengths.isNotEmpty()) {
                val current = _cellState.value
                val primarySig = cellSignalStrengths.first()
                val dbm = primarySig.dbm
                if (dbm != CellInfo.UNAVAILABLE && dbm != current.rsrp) {
                    _cellState.value = current.copy(rsrp = dbm)
                }
            }
        }
    }

    // Helper functions to handle OEM sentinels
    private fun sanitizeInt(value: Int): Int {
        return if (value == CellInfo.UNAVAILABLE || value == 2147483647) 0 else value
    }

    private fun sanitizeLong(value: Long): Long {
        return if (value == CellInfo.UNAVAILABLE_LONG || value == Long.MAX_VALUE) 0L else value
    }

    private fun sanitizeSignal(value: Int): Int {
        return if (value == CellInfo.UNAVAILABLE || value == 2147483647 || value == -2147483648) -140 else value
    }

    // --- SIMULATED CELLS ENGINE ---

    private fun setupMockCellState() {
        val defaultModel = CellModel(
            tech = "5G SA",
            mcc = simMcc,
            mnc = simMnc,
            operatorName = simOperator,
            cellId = 1234567,
            nodebId = 88888,
            sectorId = 1,
            pci = 412,
            tac = 1024,
            arfcn = 126800,
            bandName = "n71 (600 MHz)",
            frequencyMhz = 622.0,
            bandwidthKhz = 20000,
            rsrp = -82,
            rsrq = -11,
            sinr = 18,
            ssRsrp = -82,
            ssRsrq = -11,
            ssSinr = 18,
            timingAdvance = 4,
            distanceEstimateMeters = 36.96, // 4 * 9.24
            signalGrade = "Excellent",
            signalGradeColorHex = 0xFF4CAF50,
            description = "High speed primary cell connection with superb signal quality."
        )
        _cellState.value = defaultModel
    }

    private fun runSimulationStep() {
        val current = _cellState.value
        val random = Random(System.currentTimeMillis())

        // Periodically hand over sector or band
        var tech = current.tech
        var bandName = current.bandName
        var arfcn = current.arfcn
        var cellId = current.cellId
        var nodebId = current.nodebId
        var sectorId = current.sectorId
        var pci = current.pci
        var tac = current.tac
        var timingAdvance = current.timingAdvance

        // 1 in 10 chance of sector handover or technology change
        if (random.nextInt(10) == 0) {
            simSectorIndex = (simSectorIndex % 3) + 1
            sectorId = simSectorIndex
            pci = (pci + 1) % 500
            cellId = (nodebId shl (36 - gnbBitLength)) + sectorId
            
            // Randomly switch bands to simulate real environment
            if (random.nextBoolean()) {
                if (random.nextBoolean()) {
                    tech = "5G SA"
                    bandName = "n41 (2.5 GHz Mid-Band)"
                    arfcn = 518000
                    tac = 1024
                    timingAdvance = random.nextInt(2, 8)
                } else {
                    tech = "4G LTE"
                    bandName = "B66 (1700/2100 MHz AWS-3)"
                    arfcn = 66436
                    tac = 24500
                    timingAdvance = random.nextInt(4, 15)
                }
            } else {
                tech = "5G SA"
                bandName = "n71 (600 MHz)"
                arfcn = 126800
                tac = 1024
                timingAdvance = random.nextInt(1, 5)
            }
        }

        // Fluctuate signal values realistically
        val deltaRsrp = random.nextInt(-3, 4)
        val rsrp = (current.rsrp + deltaRsrp).coerceIn(-120, -50)
        
        val deltaRsrq = random.nextInt(-2, 3)
        val rsrq = (current.rsrq + deltaRsrq).coerceIn(-20, -6)

        val deltaSinr = random.nextInt(-3, 4)
        val sinr = (current.sinr + deltaSinr).coerceIn(-5, 30)

        // Calculate distance based on timing advance
        // 5G steps are ~9.24 meters, LTE is ~78.12 meters
        val distEst = if (tech.contains("5G")) {
            timingAdvance * 9.24
        } else {
            timingAdvance * 78.12
        }

        // Neighbors simulation
        val neighbors = listOf(
            NeighborCell(pci = (pci + 5) % 500, band = bandName, arfcn = arfcn, rsrp = rsrp - random.nextInt(6, 12), tech = if (tech.contains("5G")) "NR" else "LTE"),
            NeighborCell(pci = (pci + 12) % 500, band = bandName, arfcn = arfcn, rsrp = rsrp - random.nextInt(10, 18), tech = if (tech.contains("5G")) "NR" else "LTE"),
            NeighborCell(pci = (pci + 24) % 500, band = "n251 (mmWave)", arfcn = 2055000, rsrp = -115, tech = "NR")
        )

        // Active carriers (Carrier Aggregation)
        val activeCarriers = if (tech.contains("5G")) {
            listOf(
                CarrierInfo(band = bandName, arfcn = arfcn, rsrp = rsrp, type = "PCC"),
                CarrierInfo(band = "n41 (2.5 GHz Mid-Band)", arfcn = 518000, rsrp = rsrp - 6, type = "SCC"),
                CarrierInfo(band = "n25 (1900 MHz PCS)", arfcn = 390000, rsrp = rsrp - 11, type = "SCC")
            )
        } else {
            listOf(
                CarrierInfo(band = bandName, arfcn = arfcn, rsrp = rsrp, type = "PCC"),
                CarrierInfo(band = "B12 (700 MHz)", arfcn = 5010, rsrp = rsrp - 8, type = "SCC"),
                CarrierInfo(band = "B2 (1900 MHz)", arfcn = 900, rsrp = rsrp - 4, type = "SCC")
            )
        }

        // Signal grade and metrics description
        val (grade, color, desc) = evaluateSignalQuality(tech, rsrp, rsrq, sinr)

        _cellState.value = CellModel(
            tech = tech,
            mcc = simMcc,
            mnc = simMnc,
            operatorName = simOperator,
            cellId = cellId,
            nodebId = nodebId,
            sectorId = sectorId,
            pci = pci,
            tac = tac,
            arfcn = arfcn,
            bandName = bandName,
            frequencyMhz = if (arfcn == 518000) 2590.0 else if (arfcn == 66436) 2110.0 else 622.0,
            bandwidthKhz = 20000,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            ssRsrp = rsrp,
            ssRsrq = rsrq,
            ssSinr = sinr,
            timingAdvance = timingAdvance,
            distanceEstimateMeters = distEst,
            signalGrade = grade,
            signalGradeColorHex = color,
            description = desc,
            neighbors = neighbors,
            activeCarriers = activeCarriers
        )
    }

    private fun evaluateSignalQuality(tech: String, rsrp: Int, rsrq: Int, sinr: Int): Triple<String, Long, String> {
        return if (rsrp >= -80 && sinr >= 15) {
            Triple(
                "Excellent",
                0xFF4CAF50,
                "Strong link with negligible packet loss. Ideal for high-definition streaming, cloud gaming, and large downloads."
            )
        } else if (rsrp >= -95 && sinr >= 8) {
            Triple(
                "Good",
                0xFF8BC34A,
                "Stable connection with minimal latency. More than adequate for web browsing, audio calls, and regular usage."
            )
        } else if (rsrp >= -110 && sinr >= 2) {
            Triple(
                "Fair",
                0xFFFFB74D,
                "Acceptable quality, but may suffer from periodic speed drops or high latency under load. Recommended to move closer to a window."
            )
        } else {
            Triple(
                "Poor",
                0xFFE57373,
                "Highly degraded link. High risk of call drops, stalling, and significant packet corruption. Check for obstructions."
            )
        }
    }
}

class CellModelBuilder {
    var tech: String = "Unknown"
    var mcc: String = "310"
    var mnc: String = "260"
    var operatorName: String = "Carrier"
    var cellId: Long = 0L
    var nodebId: Long = 0L
    var sectorId: Int = 0
    var pci: Int = 0
    var tac: Int = 0
    var arfcn: Int = 0
    var bandName: String = "Unknown"
    var frequencyMhz: Double = 0.0
    var bandwidthKhz: Int = 0
    var rsrp: Int = -140
    var rsrq: Int = -20
    var sinr: Int = -10
    var ssRsrp: Int = -140
    var ssRsrq: Int = -20
    var ssSinr: Int = -10
    var csiRsrp: Int = -140
    var csiRsrq: Int = -20
    var csiSinr: Int = -10
    var timingAdvance: Int = 0
    var distanceEstimateMeters: Double = 0.0
    var neighbors: List<NeighborCell> = emptyList()
    var activeCarriers: List<CarrierInfo> = emptyList()

    fun build(): CellModel {
        val (grade, color, desc) = when {
            rsrp >= -80 && sinr >= 15 -> Triple("Excellent", 0xFF4CAF50, "Strong link with negligible packet loss.")
            rsrp >= -95 && sinr >= 8 -> Triple("Good", 0xFF8BC34A, "Stable connection with minimal latency.")
            rsrp >= -110 && sinr >= 2 -> Triple("Fair", 0xFFFFB74D, "Acceptable quality, but may suffer from periodic speed drops.")
            else -> Triple("Poor", 0xFFE57373, "Highly degraded link. High risk of call drops.")
        }

        // Compute distance based on timing advance
        val distEst = if (tech.contains("5G")) {
            timingAdvance * 9.24
        } else {
            timingAdvance * 78.12
        }

        return CellModel(
            tech = tech,
            mcc = mcc,
            mnc = mnc,
            operatorName = operatorName,
            cellId = cellId,
            nodebId = nodebId,
            sectorId = sectorId,
            pci = pci,
            tac = tac,
            arfcn = arfcn,
            bandName = bandName,
            frequencyMhz = frequencyMhz,
            bandwidthKhz = bandwidthKhz,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            ssRsrp = ssRsrp,
            ssRsrq = ssRsrq,
            ssSinr = ssSinr,
            csiRsrp = csiRsrp,
            csiRsrq = csiRsrq,
            csiSinr = csiSinr,
            timingAdvance = timingAdvance,
            distanceEstimateMeters = distEst,
            signalGrade = grade,
            signalGradeColorHex = color,
            description = desc,
            neighbors = neighbors,
            activeCarriers = activeCarriers.ifEmpty { listOf(CarrierInfo(band = bandName, arfcn = arfcn, rsrp = rsrp, type = "PCC")) }
        )
    }
}

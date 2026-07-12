package com.example.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationTracker(
    private val context: Context
) : SensorEventListener {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _deviceHeading = MutableStateFlow(0f)
    val deviceHeading: StateFlow<Float> = _deviceHeading.asStateFlow()

    private var locationCallback: LocationCallback? = null

    private var gravityValues = FloatArray(3)
    private var geomagneticValues = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    fun startLocationTracking() {
        if (locationCallback != null) {
            stopLocationTracking()
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationTracker", "Fine location permission missing; no location updates will be delivered")
            return
        }

        // Start compass only when tracking starts
        startCompass()

        // Seed with the last known fix immediately so consumers aren't stuck on null
        // while waiting for the first fresh update to arrive.
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    _userLocation.value = lastLoc
                }
            }
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "Location permission missing during lastLocation retrieve", e)
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { _userLocation.value = it }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationCallback = callback
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "Location permission missing during requestLocationUpdates", e)
            locationCallback = null
        }
    }

    fun stopLocationTracking() {
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error removing location updates", e)
        }
        locationCallback = null
        stopCompass()
    }

    private fun startCompass() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            // Fallback to orientation
            val orientation = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
            if (orientation != null) {
                sensorManager.registerListener(this, orientation, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private fun stopCompass() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
            _deviceHeading.value = event.values[0]
            return
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravityValues, 0, event.values.size)
            hasGravity = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagneticValues, 0, event.values.size)
            hasGeomagnetic = true
        }

        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravityValues, geomagneticValues)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                // Convert radians to degrees, normalized to 0-360
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                _deviceHeading.value = (azimuth + 360) % 360
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /**
         * Reverse geocodes a latitude/longitude to a street address. Static so callers
         * that don't need a full LocationTracker (sensors, fused location client) can
         * still resolve an address, e.g. when caching a newly-discovered tower.
         */
        suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
            val geocoder = Geocoder(context, Locale.getDefault())
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    return@withContext address.getAddressLine(0) ?: "${address.locality ?: "Unknown"}, ${address.adminArea ?: "Unknown"}"
                }
            } catch (e: Exception) {
                Log.e("LocationTracker", "Reverse geocode failed", e)
            }
            return@withContext String.format(Locale.US, "Lat: %.5f, Lon: %.5f", lat, lon)
        }

        /**
         * Calculate distance between two coordinates in meters
         */
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }

        /**
         * Calculate compass bearing between two coordinates in degrees (0-360)
         */
        fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val userLoc = Location("user").apply {
                latitude = lat1
                longitude = lon1
            }
            val destLoc = Location("dest").apply {
                latitude = lat2
                longitude = lon2
            }
            val bearing = userLoc.bearingTo(destLoc)
            return (bearing + 360) % 360
        }
    }
}

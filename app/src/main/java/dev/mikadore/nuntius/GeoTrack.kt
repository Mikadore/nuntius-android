package dev.mikadore.nuntius

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class GeoTrack : Service(), LocationListener {

    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            Log.w(TAG, "Location permission is missing; stopping tracking")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification("Waiting for location…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (exception: SecurityException) {
            Log.e(TAG, "Could not start the location foreground service", exception)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Location foreground service is not configured correctly", exception)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val provider = if (
            fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }

        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, this)
            locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Location permission was revoked while starting tracking", exception)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "No location provider is available", exception)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val message = "${location.latitude}, ${location.longitude}"
        Log.d(TAG, "Location: $message")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    @Deprecated("Deprecated in the platform API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(content: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GeoTrack is running")
            .setContentText(content)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "GeoTrack"
        private const val CHANNEL_ID = "geotrack_location"
        private const val NOTIFICATION_ID = 1
        private const val LOCATION_INTERVAL_MS = 5_000L
    }
}

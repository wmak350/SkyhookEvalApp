package com.wilmak.skyhookevalapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.ContextCompat.getSystemService
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat.getSystemService

class LocationUpdateForgroundService : Service() {

    private lateinit var mExecutor: ScheduledThreadPoolExecutor;
    companion object {
        const val SKYHOOK_EVALAPP_START_PERIODIC_UPDATE = "SHYHOOK_EVALAPP.action.start_periodic_loc_updates"
        const val SKYHOOK_EVALAPP_STOP_PERIODIC_UPDATE = "SHYHOOK_EVALAPP.action.stop_periodic_loc_updates"
        val CHANNEL_ID = "ForegroundServiceChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null)
            return START_STICKY
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foreground Service")
            .setContentText("Foreground Periodic Location Updates starting...")
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentIntent(pendingIntent)
            .build()

        val notificationMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationMgr.notify(0, notification)
        //startForeground(1, notification)

        when (intent.action) {
            SKYHOOK_EVALAPP_START_PERIODIC_UPDATE -> startPeriodicLocationUpdates()
            SKYHOOK_EVALAPP_STOP_PERIODIC_UPDATE -> stopPeriodicLocationUpdates()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager!!.createNotificationChannel(serviceChannel)
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startPeriodicLocationUpdates() {
        mExecutor = ScheduledThreadPoolExecutor(2)
        mExecutor.scheduleAtFixedRate(
            object: Runnable {
                override fun run() {
                    val intent = Intent(SKYHOOK_EVALAPP_START_PERIODIC_UPDATE)
                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                    sendBroadcast(intent)
                }
            }, 0, 60 * 1000, TimeUnit.MILLISECONDS)
    }

    private fun stopPeriodicLocationUpdates() {
        mExecutor.shutdown()
    }
}

package com.gemini.floatingcompanion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gemini.floatingcompanion.R
import com.gemini.floatingcompanion.overlay.FloatingBubbleManager
import com.gemini.floatingcompanion.ui.MainActivity

class FloatingBubbleService : Service() {

    private var bubbleManager: FloatingBubbleManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "FloatingBubbleService created")
        createNotificationChannel()
        startForegroundServiceNotification()

        bubbleManager = FloatingBubbleManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "FloatingBubbleService started with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_TRANSLATION -> bubbleManager?.startLiveVideoTranslation()
            ACTION_STOP_TRANSLATION -> bubbleManager?.stopLiveVideoTranslation()
            ACTION_SHOW_BUBBLE -> bubbleManager?.showBubble()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_gemini_sparkle)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private var isRecordingActive = false
    private var isMediaProjectionActive = false

    fun updateForegroundTypes(
        isRecording: Boolean = isRecordingActive,
        isMediaProjection: Boolean = isMediaProjectionActive
    ) {
        isRecordingActive = isRecording
        isMediaProjectionActive = isMediaProjection
        startForegroundServiceNotification(isRecording, isMediaProjection)
    }

    private fun startForegroundServiceNotification(
        isRecording: Boolean = isRecordingActive,
        isMediaProjection: Boolean = isMediaProjectionActive
    ) {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (isRecording) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (isMediaProjection) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (isRecording) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (isMediaProjection) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingBubbleService destroyed")
        bubbleManager?.destroy()
        bubbleManager = null
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val CHANNEL_ID = "gemini_companion_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_TRANSLATION = "com.gemini.floatingcompanion.ACTION_START_TRANSLATION"
        const val ACTION_STOP_TRANSLATION = "com.gemini.floatingcompanion.ACTION_STOP_TRANSLATION"
        const val ACTION_SHOW_BUBBLE = "com.gemini.floatingcompanion.ACTION_SHOW_BUBBLE"

        @Volatile
        private var instance: FloatingBubbleService? = null

        fun setMicrophoneActive(context: Context, isRecording: Boolean) {
            instance?.updateForegroundTypes(isRecording = isRecording)
        }

        fun setMediaProjectionActive(context: Context, isMediaProjection: Boolean) {
            instance?.updateForegroundTypes(isMediaProjection = isMediaProjection)
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }
}

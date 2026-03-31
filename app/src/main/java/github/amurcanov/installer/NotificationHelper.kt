package github.amurcanov.installer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val NOTIFICATION_ID = 1001

    fun show(context: Context, isRussian: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("install_channel", "Installations", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        
        val title = if (isRussian) "Установка приложения..." else "Installing application..."
        val text = if (isRussian) "Пожалуйста, подождите" else "Please wait"
        
        val builder = NotificationCompat.Builder(context, "install_channel")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true) // Cannot swipe away
            .setProgress(0, 0, true) // Indeterminate progress
            
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}

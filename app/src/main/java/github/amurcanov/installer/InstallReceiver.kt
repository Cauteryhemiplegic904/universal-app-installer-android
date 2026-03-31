package github.amurcanov.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        
        Log.d("ApkInstaller", "Install status: $status, message: $message")
        
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            NotificationHelper.dismiss(context)
        }
        
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            
            if (confirmationIntent != null) {
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmationIntent)
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            InstallState.events.tryEmit(InstallStatus.Success)
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            InstallState.events.tryEmit(InstallStatus.Cancelled)
        } else {
            InstallState.events.tryEmit(InstallStatus.Error(message ?: "Unknown error"))
        }
    }
}

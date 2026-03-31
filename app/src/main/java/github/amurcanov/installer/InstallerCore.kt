package github.amurcanov.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InstallerCore {
    suspend fun install(context: Context, uris: List<Uri>, updateStatus: (String, Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            var index = 0
            for (uri in uris) {
                val input = if (uri.scheme == "file") {
                    java.io.File(uri.path!!).inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                }
                
                input?.use { inStr ->
                    session.openWrite("base_or_split_$index", 0, -1).use { outStr ->
                        val buffer = ByteArray(65536)
                        var length: Int
                        while (inStr.read(buffer).also { length = it } >= 0) {
                            outStr.write(buffer, 0, length)
                        }
                        session.fsync(outStr)
                    }
                }
                index++
            }
            
            // To be precise we need a BroadcastReceiver to get installation result.
            // For now, commit to trigger Android package installer interface
            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = "github.amurcanov.installer.ACTION_INSTALL_COMPLETE"
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val broadcastIntent = PendingIntent.getBroadcast(
                context, 
                sessionId, 
                intent, 
                flags
            )
            
            updateStatus("Committing session...", 0.9f)
            
            val prefs = context.getSharedPreferences("installer_prefs", Context.MODE_PRIVATE)
            val isRussian = prefs.getBoolean("isRussian", true)
            NotificationHelper.show(context, isRussian)
            
            session.commit(broadcastIntent.intentSender)
            // Note: After commit, session is closed automatically
        }
    }
}

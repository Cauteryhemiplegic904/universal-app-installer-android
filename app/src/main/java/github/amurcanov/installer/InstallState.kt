package github.amurcanov.installer

import kotlinx.coroutines.flow.MutableSharedFlow

sealed class InstallStatus {
    object Idle : InstallStatus()
    data class Preparing(val name: String) : InstallStatus()
    data class Extracting(val count: Int) : InstallStatus()
    object Installing : InstallStatus()
    object Success : InstallStatus()
    object Cancelled : InstallStatus()
    data class Error(val message: String) : InstallStatus()
}

object InstallState {
    val events = MutableSharedFlow<InstallStatus>(extraBufferCapacity = 1)
}

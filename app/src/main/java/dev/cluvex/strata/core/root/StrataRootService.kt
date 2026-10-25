package dev.cluvex.strata.core.root

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager

class StrataRootService : RootService() {
    override fun onBind(intent: Intent): IBinder = FileSystemManager.getService()
}

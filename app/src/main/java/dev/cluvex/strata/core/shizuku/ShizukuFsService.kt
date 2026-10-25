package dev.cluvex.strata.core.shizuku

import android.os.IBinder
import com.topjohnwu.superuser.nio.FileSystemManager

class ShizukuFsService : IShizukuFileService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun getFileSystemService(): IBinder = FileSystemManager.getService()
}

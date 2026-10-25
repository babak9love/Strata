package dev.cluvex.strata.core.share

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ipfilter.SessionFilter
import org.apache.ftpserver.listener.Listener
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.InetSocketAddress

data class FtpShareConfig(
    val root: File,
    val port: Int = 2121,
    val username: String = "strata",
    val password: String,
    val anonymous: Boolean = false,
    val readOnly: Boolean = false,
    val bindAddress: String? = null,
    val passivePorts: String = "50000-50100",
    val idleSeconds: Int = 300,
)

class LanFtpServer(private val cfg: FtpShareConfig) {
    private var server: FtpServer? = null
    private var listener: Listener? = null

    fun start(): Int {
        require(cfg.username.isNotBlank() && !cfg.username.equals("anonymous", ignoreCase = true)) { tr(R.string.share_user_not_anonymous) }
        require(cfg.password.isNotEmpty()) { tr(R.string.share_set_password) }
        val factory = FtpServerFactory()
        val l = ListenerFactory().apply {
            port = cfg.port
            serverAddress = cfg.bindAddress
            idleTimeout = cfg.idleSeconds
            sessionFilter = SessionFilter { s -> (s.remoteAddress as? InetSocketAddress)?.address?.let(::isLanPeer) == true }
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passivePorts = cfg.passivePorts
                isActiveEnabled = false
                isPassiveIpCheck = true
            }.createDataConnectionConfiguration()
        }.createListener()
        factory.addListener("default", l)
        factory.connectionConfig = ConnectionConfigFactory().apply {
            isAnonymousLoginEnabled = cfg.anonymous
            maxLoginFailures = 3
            loginFailureDelay = 1000
        }.createConnectionConfig()
        factory.fileSystem = ConfinedFileSystemFactory(NativeFileSystemFactory())

        fun account(name: String, password: String?, write: Boolean) = BaseUser().apply {
            this.name = name
            this.password = password
            homeDirectory = cfg.root.absolutePath
            authorities = buildList<Authority> {
                add(ConcurrentLoginPermission(10, 10))
                if (write) add(WritePermission())
            }
            maxIdleTime = cfg.idleSeconds
        }
        factory.userManager.save(account(cfg.username, cfg.password, write = !cfg.readOnly))
        if (cfg.anonymous) factory.userManager.save(account("anonymous", null, write = false))

        val s = factory.createServer()
        s.start()
        server = s
        listener = l
        return l.port
    }

    fun stop() {
        server?.stop()
        server = null
    }

    val isRunning: Boolean get() = server?.isStopped == false
}

class ConfinedFileSystemFactory(private val inner: FileSystemFactory) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView {
        val home = File(user.homeDirectory).canonicalFile
        val view = inner.createFileSystemView(user)
        fun inside(f: FtpFile): Boolean {
            val c = (f.physicalFile as File).canonicalFile
            return c == home || c.path.startsWith(home.path + File.separator)
        }
        return object : FileSystemView by view {
            override fun getFile(file: String): FtpFile = view.getFile(file).also { if (!inside(it)) throw FtpException("Outside the shared folder") }
            override fun changeWorkingDirectory(dir: String): Boolean = inside(view.getFile(dir)) && view.changeWorkingDirectory(dir)
        }
    }
}

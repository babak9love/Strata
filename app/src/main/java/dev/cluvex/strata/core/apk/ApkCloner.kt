package dev.cluvex.strata.core.apk

import com.reandroid.apk.ApkModule
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.i18n.tr
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry

object ApkCloner {
    private const val ID_ICON = 0x01010002
    private const val ID_NAME = 0x01010003
    private const val ID_AUTHORITIES = 0x01010018
    private const val ID_DRAWABLE = 0x01010199
    private const val ID_TARGET_ACTIVITY = 0x01010202
    private const val ID_ROUND_ICON = 0x0101052c
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun validatePackage(pkg: String): String? = when {
        pkg.isBlank() -> tr(R.string.apk_clone_enter_package)
        pkg.length > 255 -> tr(R.string.apk_clone_too_long)
        !PACKAGE.matches(pkg) -> tr(R.string.apk_clone_package_rule)
        else -> null
    }

    fun suggestPackage(oldPackage: String): String =
        if (oldPackage.isBlank()) "com.example.clone" else "$oldPackage.clone"

    data class CloneResult(val oldPackage: String, val newPackage: String, val label: String?, val iconResource: String? = null)

    val STEP_READ: String get() = tr(R.string.apk_step_read)
    val STEP_RENAME: String get() = tr(R.string.apk_step_rename)
    val STEP_REBASE: String get() = tr(R.string.apk_step_rebase)
    val STEP_ICON: String get() = tr(R.string.apk_step_icon)
    val STEP_WRITE: String get() = tr(R.string.apk_step_write)

    fun clone(input: File, output: File, newPackage: String, newLabel: String?, icon: CloneIcon? = null, log: BuildListener = BuildListener.NONE): CloneResult {
        validatePackage(newPackage)?.let { throw IllegalArgumentException(it) }
        require(input.isFile) { tr(R.string.apk_error_not_a_file, input.name) }
        log.step(STEP_READ)
        ApkModule.loadApkFile(input).use { module ->
            if (!module.hasAndroidManifest()) throw IOException(tr(R.string.apk_clone_no_manifest))
            val manifest = module.androidManifest
            val oldPackage = manifest.packageName?.takeIf { it.isNotBlank() } ?: throw IOException(tr(R.string.apk_clone_no_package))
            require(oldPackage != newPackage) { tr(R.string.apk_clone_same_package) }
            log.info("$oldPackage · ${BuildRun.size(input.length())}")

            log.step(STEP_RENAME)
            val absolute = absolutizeComponents(manifest, oldPackage)
            if (absolute > 0) log.info(BuildRun.count(absolute, "component name") + " made absolute")
            log.info("$oldPackage → $newPackage")
            log.step(STEP_REBASE)
            val authorities = renameAuthorities(manifest, oldPackage, newPackage)
            val permissions = renameOwnPermissions(manifest, oldPackage, newPackage)
            log.info(BuildRun.count(authorities, "provider authority", "provider authorities") + " · " + BuildRun.count(permissions, "own permission"))

            module.setPackageName(newPackage)
            val label = newLabel?.trim()?.takeIf { it.isNotEmpty() }
            label?.let {
                manifest.setApplicationLabel(it)
                log.info("Label \"$it\"")
            }
            val iconResource = icon?.let {
                log.step(STEP_ICON)
                replaceIcon(module, manifest, it).also { res -> log.info("@$res · legacy PNGs and an adaptive icon") }
            }

            manifest.refreshFull()
            if (module.hasTableBlock()) module.tableBlock.refresh()

            log.step(STEP_WRITE)
            output.parentFile?.mkdirs()
            try {
                module.writeApk(output)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            log.info("Unsigned · ${BuildRun.size(output.length())}")
            return CloneResult(oldPackage, newPackage, label, iconResource)
        }
    }

    private fun replaceIcon(module: ApkModule, manifest: AndroidManifestBlock, icon: CloneIcon): String {
        if (!module.hasTableBlock()) throw IOException(tr(R.string.apk_clone_no_table))
        val pkg = module.tableBlock.pickOne() ?: throw IOException(tr(R.string.apk_clone_no_res_package))
        val type = if (pkg.getSpecTypePair("mipmap") != null) "mipmap" else "drawable"
        val name = freeName(pkg, type, "strata_clone_icon")

        fun addFile(qualifiers: String, entryName: String, ext: String, bytes: ByteArray): Int {
            val path = freePath(module, "res/$type-$qualifiers/$entryName.$ext")
            module.add(ByteInputSource(bytes, path).apply { method = if (ext == "png") ZipEntry.STORED else ZipEntry.DEFLATED })
            if (ext == "png") module.uncompressedFiles.addPath(path)
            val entry = pkg.getOrCreate(qualifiers, type, entryName)
            entry.setValueAsString(path)
            return entry.resourceId
        }

        val id = icon.legacy.map { (d, png) -> addFile("${d.qualifier}-v4", name, "png", png) }.distinct().single()
        val foreground = icon.foreground.map { (d, png) -> addFile("${d.qualifier}-v4", "${name}_foreground", "png", png) }.distinct().single()
        val background = icon.background.map { (d, png) -> addFile("${d.qualifier}-v4", "${name}_background", "png", png) }.distinct().single()
        val adaptive = addFile("anydpi-v26", name, "xml", adaptiveIconXml(pkg, background, foreground))
        check(adaptive == id) { "The adaptive icon must share the legacy icon's resource id" }

        val app = manifest.applicationElement ?: throw IOException(tr(R.string.apk_clone_no_application))
        app.getOrCreateAndroidAttribute("icon", ID_ICON).setValueAsResourceId(id)
        app.searchAttributeByResourceId(ID_ROUND_ICON)?.setValueAsResourceId(id)
        for (tag in listOf("activity", "activity-alias")) {
            for (el in manifest.listApplicationElementsByTag(tag)) {
                el.searchAttributeByResourceId(ID_ICON)?.setValueAsResourceId(id)
                el.searchAttributeByResourceId(ID_ROUND_ICON)?.setValueAsResourceId(id)
            }
        }
        return "$type/$name"
    }

    private fun adaptiveIconXml(pkg: PackageBlock, background: Int, foreground: Int): ByteArray {
        val doc = ResXmlDocument()
        doc.setPackageBlock(pkg)
        val root = doc.newElement("adaptive-icon")
        root.newNamespace(ANDROID_NS, "android")
        for ((tag, drawable) in listOf("background" to background, "foreground" to foreground)) {
            root.newElement(tag).getOrCreateAndroidAttribute("drawable", ID_DRAWABLE).setValueAsResourceId(drawable)
        }
        doc.refreshFull()
        return doc.bytes
    }

    private fun freeName(pkg: PackageBlock, type: String, base: String): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { pkg.getResource(type, it) == null }

    private fun freePath(module: ApkModule, path: String): String {
        if (!module.containsFile(path)) return path
        val dot = path.lastIndexOf('.')
        return generateSequence(2) { it + 1 }.map { path.substring(0, dot) + "_$it" + path.substring(dot) }.first { !module.containsFile(it) }
    }

    private fun absolutizeComponents(manifest: AndroidManifestBlock, pkg: String): Int {
        var changed = 0
        manifest.applicationElement?.let { if (absolutize(it, ID_NAME, pkg)) changed++ }
        for (tag in COMPONENT_TAGS) {
            for (el in manifest.listApplicationElementsByTag(tag)) {
                if (absolutize(el, ID_NAME, pkg)) changed++
                if (tag == "activity-alias" && absolutize(el, ID_TARGET_ACTIVITY, pkg)) changed++
            }
        }
        return changed
    }

    private fun absolutize(el: ResXmlElement, resId: Int, pkg: String): Boolean {
        val attr = el.searchAttributeByResourceId(resId) ?: return false
        val value = attr.valueAsString ?: return false
        val absolute = absoluteComponentName(value, pkg)
        if (absolute == value) return false
        attr.setValueAsString(absolute)
        return true
    }

    internal fun absoluteComponentName(value: String, pkg: String): String = when {
        value.startsWith(".") -> pkg + value
        !value.contains(".") -> "$pkg.$value"
        else -> value
    }

    private fun renameAuthorities(manifest: AndroidManifestBlock, oldPkg: String, newPkg: String): Int {
        var changed = 0
        for (el in manifest.listApplicationElementsByTag("provider")) {
            val attr = el.searchAttributeByResourceId(ID_AUTHORITIES) ?: continue
            val value = attr.valueAsString ?: continue
            val renamed = value.split(';').joinToString(";") { replacePackagePrefix(it, oldPkg, newPkg) }
            if (renamed != value) {
                attr.setValueAsString(renamed)
                changed++
            }
        }
        return changed
    }

    private fun renameOwnPermissions(manifest: AndroidManifestBlock, oldPkg: String, newPkg: String): Int {
        val root = manifest.manifestElement ?: return 0
        var changed = 0
        for (tag in PERMISSION_TAGS) {
            for (el in root.getElements(tag).asSequence().toList()) {
                val attr = el.searchAttributeByResourceId(ID_NAME) ?: continue
                val value = attr.valueAsString ?: continue
                val renamed = replacePackagePrefix(value, oldPkg, newPkg)
                if (renamed != value) {
                    attr.setValueAsString(renamed)
                    changed++
                }
            }
        }
        return changed
    }

    internal fun replacePackagePrefix(s: String, oldPkg: String, newPkg: String): String = when {
        s == oldPkg -> newPkg
        s.startsWith("$oldPkg.") -> newPkg + s.substring(oldPkg.length)
        else -> s
    }

    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")
    private val PERMISSION_TAGS = listOf("permission", "permission-group", "permission-tree", "uses-permission", "uses-permission-sdk-23")
}

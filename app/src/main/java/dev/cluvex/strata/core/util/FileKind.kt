package dev.cluvex.strata.core.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.cluvex.strata.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector
import dev.cluvex.strata.core.model.FileItem

enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, APK, CODE, TEXT, PDF, FONT, DOCUMENT, ENCRYPTED, BINARY;

    val icon: ImageVector
        get() = when (this) {
            FOLDER -> Icons.Rounded.Folder
            IMAGE -> Icons.Rounded.Image
            VIDEO -> Icons.Rounded.Movie
            AUDIO -> Icons.Rounded.Audiotrack
            ARCHIVE -> Icons.Rounded.FolderZip
            APK -> Icons.Rounded.Android
            CODE -> Icons.Rounded.Code
            TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
            PDF -> Icons.Rounded.PictureAsPdf
            FONT -> Icons.Rounded.FontDownload
            DOCUMENT -> Icons.Rounded.Description
            ENCRYPTED -> Icons.Rounded.Lock
            BINARY -> Icons.AutoMirrored.Rounded.InsertDriveFile
        }

    @get:StringRes
    val label: Int
        get() = when (this) {
            FOLDER -> R.string.common_folder
            IMAGE -> R.string.model_open_as_image
            VIDEO -> R.string.model_open_as_video
            AUDIO -> R.string.model_open_as_audio
            ARCHIVE -> R.string.files_kind_archive
            APK -> R.string.files_kind_apk
            CODE -> R.string.files_kind_code
            TEXT -> R.string.model_open_as_text
            PDF -> R.string.files_kind_pdf
            FONT -> R.string.files_kind_font
            DOCUMENT -> R.string.files_kind_document
            ENCRYPTED -> R.string.files_kind_encrypted
            BINARY -> R.string.common_file
        }
}

object FileKinds {

    private val image = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico", "svg")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "ts", "m4v")
    private val audio = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "mid", "amr")
    private val archive = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "zstd", "lz4",
        "jar", "apks", "xapk", "apkm", "war",
    )
    private val code = setOf(
        "kt", "kts", "java", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
        "py", "rb", "js", "ts", "tsx", "jsx", "php", "lua", "dart", "sh", "bat",
        "gradle", "smali", "asm", "sql", "html", "css", "scss", "vue", "zig",
    )
    private val text = setOf(
        "txt", "md", "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf",
        "properties", "prop", "log", "csv", "tsv", "mtl", "mtsx", "diff", "patch",
    )
    private val font = setOf("ttf", "otf", "woff", "woff2", "ttc")
    private val document = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "epub")

    fun of(item: FileItem): FileKind {
        if (item.isDirectory) return FileKind.FOLDER
        val ext = item.extension
        return when {
            ext == "apk" -> FileKind.APK
            ext == "pdf" -> FileKind.PDF
            ext == "senc" -> FileKind.ENCRYPTED
            ext in image -> FileKind.IMAGE
            ext in video -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext in archive -> FileKind.ARCHIVE
            ext in code -> FileKind.CODE
            ext in text -> FileKind.TEXT
            ext in font -> FileKind.FONT
            ext in document -> FileKind.DOCUMENT
            else -> FileKind.BINARY
        }
    }

    /** Branded vector icon for a few well-known file types, or null to use the generic icon of its kind. */
    @DrawableRes
    fun customIcon(item: FileItem): Int? {
        if (item.isDirectory) return R.drawable.ic_folder
        val name = item.name.lowercase()
        // .env, .env.local, .env.production, prod.env ...
        if (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) return R.drawable.ic_env
        // ".ts" is TypeScript source or an MPEG-TS video: sources are tiny, videos are large.
        if (item.extension == "ts") return if (item.size < 1_000_000L) R.drawable.ic_lang_ts else null
        return customIconByExtension(item.extension)
    }

    @DrawableRes
    private fun customIconByExtension(extension: String): Int? = when (extension) {
        "py", "pyw", "pyi" -> R.drawable.ic_python
        "sh", "bash", "zsh" -> R.drawable.ic_shell
        "txt", "log" -> R.drawable.ic_text
        "json", "jsonc", "json5" -> R.drawable.ic_json
        "md", "markdown" -> R.drawable.ic_markdown
        "html", "htm", "xhtml" -> R.drawable.ic_html
        "xml" -> R.drawable.ic_xml
        "yaml", "yml" -> R.drawable.ic_yaml
        "pdf" -> R.drawable.ic_pdf
        "apk", "apks", "xapk", "apkm" -> R.drawable.ic_apk
        "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus" -> R.drawable.ic_audio
        "doc" -> R.drawable.ic_doc
        "docx" -> R.drawable.ic_docx
        "odt" -> R.drawable.ic_odt
        "rtf" -> R.drawable.ic_rtf
        "xls" -> R.drawable.ic_xls
        "xlsx" -> R.drawable.ic_xlsx
        "ods" -> R.drawable.ic_ods
        "ppt" -> R.drawable.ic_ppt
        "pptx" -> R.drawable.ic_pptx
        "odp" -> R.drawable.ic_odp
        "kt", "kts" -> R.drawable.ic_kotlin
        "java" -> R.drawable.ic_lang_java
        "js", "mjs", "cjs", "jsx" -> R.drawable.ic_lang_js
        "tsx" -> R.drawable.ic_lang_ts
        "css", "scss", "sass", "less" -> R.drawable.ic_lang_css
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> R.drawable.ic_lang_cpp
        "c", "h" -> R.drawable.ic_lang_c
        "cs" -> R.drawable.ic_lang_cs
        "go" -> R.drawable.ic_lang_go
        "rs" -> R.drawable.ic_lang_rs
        "php" -> R.drawable.ic_lang_php
        "rb" -> R.drawable.ic_lang_rb
        "sql" -> R.drawable.ic_lang_sql
        "vue" -> R.drawable.ic_lang_vue
        "7z" -> R.drawable.ic_7z
        "rar" -> R.drawable.ic_rar
        "zip", "tar", "gz", "tgz", "bz2", "xz" -> R.drawable.ic_archive
        "jks", "keystore", "p12", "pfx", "pem" -> R.drawable.ic_keystore // keys & certificates
        else -> null
    }

    /**
     * Extension text for the auto-generated icon (coloured tile with the format written inside), or null when the file
     * already has a branded icon, is a folder / picture / video (which have their own thumbnails) or has no extension.
     */
    fun autoIconLabel(item: FileItem): String? {
        if (item.isDirectory || customIcon(item) != null) return null
        val kind = of(item)
        if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) return null
        return item.extension.ifEmpty { null }
    }

    fun isImage(item: FileItem) = of(item) == FileKind.IMAGE
    fun isVideo(item: FileItem) = of(item) == FileKind.VIDEO
    /** .apk files and split-APK bundles (.apks / .xapk / .apkm) show the app's own icon. */
    fun hasApkIcon(item: FileItem): Boolean = !item.isDirectory && (item.extension == "apk" || item.extension in setOf("apks", "xapk", "apkm"))

    fun isThumbnailable(item: FileItem) = isImage(item) || isVideo(item) || hasApkIcon(item)
    fun isTextEditable(item: FileItem): Boolean {
        val k = of(item)
        return k == FileKind.TEXT || k == FileKind.CODE
    }
    fun isArchive(item: FileItem) = of(item) == FileKind.ARCHIVE
}

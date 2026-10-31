import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "dev.cluvex.strata"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.cluvex.strata"
        minSdk = 24
        targetSdk = 36
        versionCode = (project.property("strata.versionCode") as String).toInt()
        versionName = project.property("strata.versionName") as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        create("minified") {
            initWith(getByName("release"))
            proguardFile("proguard-minified-test.pro")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            matchingFallbacks += "release"
        }
    }
    testBuildType = (findProperty("strata.testBuildType") as String?) ?: "debug"

    splits {
        abi {
            isEnable = providers.gradleProperty("strata.splitApks").map { it.toBoolean() }.getOrElse(false)
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,LICENSE.md,NOTICE,NOTICE.txt,NOTICE.md}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{INDEX.LIST,DEPENDENCIES,maven/**}"
            excludes += "/**/module-info.class"
            excludes += "/META-INF/services/javax.annotation.processing.Processor"
            pickFirsts += "/META-INF/versions/9/module-info.class"
            merges += "/META-INF/services/**"
            excludes += "/META-INF/services/java.net.spi.InetAddressResolverProvider"
            excludes += "/META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor"
            excludes += "/resources/api_database.ser"
            excludes += "/LICENSE"
            // The FFmpeg AAR also ships x86 libraries; Strata only targets arm64-v8a, armeabi-v7a and x86_64.
            excludes += "**/x86/*.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        // app/libs/media3ext-release.aar is built with a newer Kotlin than this project; read its metadata anyway.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

val nativeDir: File = rootProject.file("native")
val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    description = "Builds native/ for the host machine (used by JVM unit tests)."
    group = "build"
    workingDir = nativeDir
    commandLine(
        "sh", "-c",
        "if command -v cargo >/dev/null 2>&1; then cargo build --release; " +
            "else echo 'cargo not found: skipping host native build'; fi",
    )
    inputs.dir(nativeDir.resolve("src"))
    inputs.file(nativeDir.resolve("Cargo.toml"))
    inputs.file(nativeDir.resolve("build.rs"))
    outputs.file(nativeDir.resolve("target/release/libstrata_native.so"))
}
tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    systemProperty("java.library.path", nativeDir.resolve("target/release").absolutePath)
    systemProperty("strata.pdfium", nativeDir.resolve("third_party/pdfium/linux-x64/libpdfium.so").absolutePath)
    maxHeapSize = "2g"
}

configurations.configureEach {
    exclude(group = "org.ow2.asm", module = "asm")
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}",
            "org.ow2.asm:asm-tree:9.10.1",
            "org.ow2.asm:asm-commons:9.10.1",
            "org.ow2.asm:asm-util:9.10.1",
            "org.ow2.asm:asm-analysis:9.10.1",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.material.kolor)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.okhttp)
    implementation(libs.okhttp.digest)
    implementation(libs.ftpserver.core)
    implementation(libs.mina.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui.compose.material3)
    // FFmpeg 9.0.1 decoders for Media3 (NextLib), built from source. See app/libs/README.md.
    implementation(files("libs/media3ext-release.aar"))

    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.jadx.smali.input)
    implementation(libs.jadx.java.input)
    implementation(libs.smali.dexlib2)
    implementation(libs.smali.baksmali)
    implementation(libs.smali)
    implementation(libs.dex2jar)
    implementation(libs.r8)
    implementation(libs.arsclib)

    implementation(libs.commons.net)
    implementation(libs.dnsjava)
    implementation(libs.sshj)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.zip4j)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.tomcat.embed.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}

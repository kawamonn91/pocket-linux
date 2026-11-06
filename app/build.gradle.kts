import java.net.URI
import java.security.MessageDigest
import java.util.Properties

/** リリース署名。keystore.properties が無い環境ではデバッグ署名のままビルドが通る */
val releaseSigning: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

/**
 * VM の生成物（GitHub Releases に置いたもの）を、vm/artifacts.properties の版と SHA-256 で取ってきて APK に入れる。
 * - libqemu.so: ABI ごとの jniLibs（実行するので nativeLibraryDir に展開させる）
 * - カーネル・system.erofs・data.img.gz: assets/vm（ゲストは常に arm64 なので 1 組だけ。初回の起動で展開する。VmImages.kt）
 * 取ってきたものは vm/prebuilt/<版>/ に残す（Git の対象外）。
 */
abstract class PrepareVmArtifacts : DefaultTask() {
    @get:InputFile
    abstract val pins: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun prepare() {
        val props = Properties().apply { pins.get().asFile.inputStream().use(::load) }
        val tag = props.getProperty("tag") ?: error("vm/artifacts.properties に tag がありません")
        val cache = cacheDir.get().dir(tag).asFile.apply { mkdirs() }
        // 前の版の生成物が APK に混ざらないように
        jniLibs.get().asFile.deleteRecursively()
        assets.get().asFile.deleteRecursively()

        fun fetch(name: String): File {
            val want = props.getProperty(name) ?: error("vm/artifacts.properties に $name がありません")
            val file = File(cache, name)
            if (file.isFile && sha256(file) == want) return file
            val url = "https://github.com/kawamonn91/pocket-linux/releases/download/$tag/$name"
            logger.lifecycle("VM の生成物を取ってきます: $url")
            val tmp = File(cache, "$name.tmp")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val actual = sha256(tmp)
            check(actual == want) { "$name の SHA-256 が合いません（$actual）" }
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            return file
        }

        for ((abi, name) in listOf("arm64-v8a" to "libqemu-arm64-v8a.so", "x86_64" to "libqemu-x86_64.so")) {
            fetch(name).copyTo(jniLibs.get().dir(abi).file("libqemu.so").asFile, overwrite = true)
        }
        val vmAssets = assets.get().dir("vm").asFile.apply { mkdirs() }
        fetch("Image").copyTo(File(vmAssets, "Image"), overwrite = true)
        fetch("system.erofs").copyTo(File(vmAssets, "system.erofs"), overwrite = true)
        // .gz のままだと AGP がアセットを展開してしまう（1 GiB の data.img が APK に入る）ので名前を変える
        fetch("data.img.gz").copyTo(File(vmAssets, "data.img.gzip"), overwrite = true)
        // 中身が変わったら、端末で展開し直す
        File(vmAssets, "VERSION").writeText(tag + "-" + props.getProperty("system.erofs").take(12) + "-" + props.getProperty("Image").take(12) + "\n")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ttech.pocketlinux"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ttech.pocketlinux"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"

        ndk {
            // QEMU は 64bit 版だけを同梱する（VM の中も arm64 だけ）
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // QEMU の実行ファイルを nativeLibraryDir に展開させる（APK 内のままでは exec できない）
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("ja", "en")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<PrepareVmArtifacts>("prepareVmArtifacts" + variant.name.replaceFirstChar(Char::uppercase)) {
            pins.set(rootProject.layout.projectDirectory.file("vm/artifacts.properties"))
            cacheDir.set(rootProject.layout.projectDirectory.dir("vm/prebuilt"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, PrepareVmArtifacts::jniLibs)
        variant.sources.assets?.addGeneratedSourceDirectory(task, PrepareVmArtifacts::assets)
    }
}

dependencies {
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

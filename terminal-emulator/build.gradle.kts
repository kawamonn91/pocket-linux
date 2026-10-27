// Termux の terminal-emulator（Apache-2.0）。https://github.com/termux/termux-app/tree/v0.118.3/terminal-emulator
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}

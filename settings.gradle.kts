pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocket-linux"

include(":app")
// Termux の端末ライブラリ（Apache-2.0）。v0.118.3 のソースをそのまま取り込んでいる
include(":terminal-emulator")
include(":terminal-view")

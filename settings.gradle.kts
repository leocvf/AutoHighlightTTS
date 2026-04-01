pluginManagement {
    repositories {
        // Primary repositories
        google()
        mavenCentral()
        gradlePluginPortal()

        // Mirrors for environments where direct Google/Plugin Portal access is restricted.
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
    }

    resolutionStrategy {
        eachPlugin {
            // Resolve Android Gradle Plugin directly to avoid plugin marker lookup failures.
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Mirrors for dependency resolution fallback.
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "MITextToSpeech"
include(":app")
include(":AutoHighlightTTS")

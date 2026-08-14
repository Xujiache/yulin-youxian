pluginManagement {
    repositories {
        // KSP 2.3.11 的 plugin marker 在阿里云上会 502，必须先走官方源。
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        mavenCentral()
        google()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

rootProject.name = "rider-android"

include(":app")

include(":core:common")
include(":core:designsystem")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:model")
include(":core:location")
include(":core:push")
include(":core:update")

include(":feature:auth")
include(":feature:shift")
include(":feature:task")
include(":feature:map")
include(":feature:exception")
include(":feature:earning")
include(":feature:message")
include(":feature:profile")

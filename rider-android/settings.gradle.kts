pluginManagement {
    repositories {
        // 中国大陆网络:阿里云镜像置前,成败关键
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
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

include(":feature:auth")
include(":feature:shift")
include(":feature:task")
include(":feature:map")
include(":feature:exception")
include(":feature:earning")
include(":feature:message")
include(":feature:profile")

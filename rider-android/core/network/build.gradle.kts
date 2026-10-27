import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 服务器地址。客户换域名或换成 IP 时不用改代码:
 *   ./gradlew assembleRelease -PRIDER_BASE_URL=https://your.domain
 * 也可以写进 gradle.properties 的 RIDER_BASE_URL。
 */
fun riderBaseUrl(default: String): String =
    (project.findProperty("RIDER_BASE_URL") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: default

val releaseBaseUrl = riderBaseUrl("https://hqhjxt.vip")
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested) {
    val uri = runCatching { URI(releaseBaseUrl) }.getOrNull()
    if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
        throw GradleException("Release RIDER_BASE_URL 必须是有效 HTTPS 地址，当前值: $releaseBaseUrl")
    }
}

android {
    namespace = "com.yulin.rider.core.network"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            // 模拟器访问开发机
            buildConfigField("String", "BASE_URL", "\"${riderBaseUrl("http://10.0.2.2:8080")}\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    // 拦截器要在 OkHttp 线程上同步取令牌与设备号
    api(project(":core:datastore"))

    api(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // api 而非 implementation:RiderApis.of() 的调用方(feature/task 等无 Hilt 插件的模块)
    // 需要 dagger.hilt 的运行时类可见
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val buildSequence = (project.findProperty("RIDER_BUILD_SEQUENCE") as String?)
    ?.toIntOrNull() ?: 1
require(buildSequence in 0..99) { "RIDER_BUILD_SEQUENCE 必须在 0..99" }
val shanghaiDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
val dateVersionCode = shanghaiDate
    .format(DateTimeFormatter.ofPattern("yyMMdd"))
    .toInt() * 100 + buildSequence
val riderVersionCode = (project.findProperty("RIDER_VERSION_CODE") as String?)
    ?.toIntOrNull() ?: dateVersionCode
require(riderVersionCode > 0) { "RIDER_VERSION_CODE 必须是正整数" }
val riderVersionName = (project.findProperty("RIDER_VERSION_NAME") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: (shanghaiDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + "." + buildSequence)

val externalSigningPropertiesFile = sequenceOf(
    System.getenv("RIDER_SIGNING_PROPERTIES_FILE"),
    System.getProperty("user.home")?.let { "$it/.yulin/rider-signing.properties" },
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .map(::file)
    .first()
val externalSigningProperties = Properties().apply {
    if (externalSigningPropertiesFile.isFile) {
        externalSigningPropertiesFile.inputStream().use(::load)
    }
}
fun signingValue(name: String): String? = sequenceOf(
    System.getenv(name),
    externalSigningProperties.getProperty(name),
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .firstOrNull()

android {
    namespace = "com.yulin.rider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yulin.rider"
        minSdk = 26
        targetSdk = 36
        // 默认 YYMMDDNN（UTC 日期 + 当日序号），正式流水线可显式传 RIDER_VERSION_CODE。
        versionCode = riderVersionCode
        versionName = riderVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 极光 SDK 的 AAR manifest 占位符;依赖暂缓接入(见 core/push),占位符保留无副作用
        manifestPlaceholders["JPUSH_PKGNAME"] = "com.yulin.rider"
        manifestPlaceholders["JPUSH_APPKEY"] = "TODO_JPUSH_APPKEY"
        manifestPlaceholders["JPUSH_CHANNEL"] = "developer-default"

        // 高德 Android Key 走 -PAMAP_KEY 或环境变量 AMAP_KEY，不要写进仓库。
        // 留空时 SDK 鉴权失败但不崩溃，地图底图空白。
        manifestPlaceholders["AMAP_KEY"] = sequenceOf(
            project.findProperty("AMAP_KEY") as String?,
            System.getenv("AMAP_KEY"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull().orEmpty()
    }

    val keystorePath = signingValue("RIDER_KEYSTORE_PATH")
    val keystoreFile = keystorePath?.let { path ->
        file(path).let { candidate ->
            if (candidate.isAbsolute) candidate else externalSigningPropertiesFile.parentFile.resolve(path)
        }
    }
    val hasKeystore = keystoreFile?.isFile == true
    val missingSigningProperties = listOf(
        "RIDER_KEYSTORE_PATH",
        "RIDER_KEYSTORE_PASSWORD",
        "RIDER_KEY_ALIAS",
        "RIDER_KEY_PASSWORD",
    ).filter { signingValue(it).isNullOrBlank() }
    if (releaseRequested && (!hasKeystore || missingSigningProperties.isNotEmpty())) {
        throw GradleException(
            "Release 签名配置不完整: keystore=${keystoreFile?.absolutePath ?: "未配置"}, " +
                "缺少属性=${missingSigningProperties.joinToString().ifBlank { "无" }}"
        )
    }

    signingConfigs {
        if (hasKeystore && missingSigningProperties.isEmpty()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingValue("RIDER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("RIDER_KEY_ALIAS")
                keyPassword = signingValue("RIDER_KEY_PASSWORD")
                // minSdk 26,v1(JAR 签名)已无设备需要,AGP 也会忽略;v2 + v3 即可
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // release 打哪些 ABI。真机全是 arm，x86/x86_64 白占位置。
    // 高德导航的 so 是大头且按 ABI 各来一份(arm64 37 MB / armeabi-v7a 25 MB)，
    // 两个都打包体 ~99 MB，微信传文件顶到上限;只打 arm64 能降到 ~70 MB。
    // 默认两个都要(armeabi-v7a 兜住极少数 32 位老机)，要小包就 -PRIDER_ABI=arm64。
    val releaseAbis = if (project.findProperty("RIDER_ABI") == "arm64") {
        listOf("arm64-v8a")
    } else {
        listOf("arm64-v8a", "armeabi-v7a")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore && missingSigningProperties.isEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters += releaseAbis
            }
        }
        debug {
            // x86_64 必须留着，否则装不进模拟器，后续没法再用模拟器验证
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // 关于页要显示版本号,推送初始化要区分 debug —— AGP 8 起 BuildConfig 默认不生成
        buildConfig = true
    }
}

tasks.register("verifyReleaseSignature") {
    group = "verification"
    description = "组装 release APK，并用 apksigner 校验签名与可选证书摘要"
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .singleOrNull()
            ?: throw GradleException("未找到唯一 release APK")
        val sdkRoot = sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
        ).filterNotNull().map(::file).firstOrNull { it.isDirectory }
            ?: throw GradleException("未设置 ANDROID_SDK_ROOT/ANDROID_HOME，无法运行 apksigner")
        val executable = sdkRoot.resolve(
            "build-tools/${android.buildToolsVersion}/apksigner" +
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".bat" else ""
        )
        if (!executable.isFile) throw GradleException("未找到 apksigner: ${executable.absolutePath}")

        val execution = providers.exec {
            commandLine(executable.absolutePath, "verify", "--verbose", "--print-certs", apk.absolutePath)
            isIgnoreExitValue = true
        }
        val verification = execution.standardOutput.asText.get() + execution.standardError.asText.get()
        if (execution.result.get().exitValue != 0) {
            throw GradleException("release APK 签名校验失败:\n$verification")
        }
        val expected = (
            signingValue("RIDER_EXPECTED_CERT_SHA256")
                ?: (project.findProperty("RIDER_EXPECTED_CERT_SHA256") as String?)
            )
            ?.replace(":", "")?.replace(" ", "")?.lowercase()
        if (!expected.isNullOrBlank()) {
            val normalizedOutput = verification.replace(":", "").replace(" ", "").lowercase()
            if (!normalizedOutput.contains(expected)) {
                throw GradleException("release APK 证书 SHA-256 与 RIDER_EXPECTED_CERT_SHA256 不一致")
            }
        }
        logger.lifecycle("[rider] release APK 签名校验通过: ${apk.name}")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    implementation(project(":core:location"))
    implementation(project(":core:push"))
    implementation(project(":core:update"))

    implementation(project(":feature:auth"))
    implementation(project(":feature:shift"))
    implementation(project(":feature:task"))
    implementation(project(":feature:map"))
    implementation(project(":feature:exception"))
    implementation(project(":feature:earning"))
    implementation(project(":feature:message"))
    implementation(project(":feature:profile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.work.runtime.ktx)

    // 高德:RiderApplication 需在 onCreate 首行调用隐私合规接口(navi-3dmap 内置定位类,与 core/location 保持同一坐标)
    implementation(libs.amap.navi3d)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.8")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

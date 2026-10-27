plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.yulin.rider.core.push"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:datastore"))
    // 设备标识与 /api/rider/devices 上报由 core:location 统一持有,推送侧只补 registrationId
    implementation(project(":core:location"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // TODO(暂缓接入): 阿里云镜像仅有 jpush 4.0.5 / jcore 2.7.4,其 AAR 的 PushReceiver
    // 带 intent-filter 但缺 android:exported,与 targetSdk 36(Android 12+ 强制要求)不兼容。
    // 待官方 5.x(cn.jiguang.sdk)可从可达仓库解析后再启用,并同步补 app 的 JPUSH_APPKEY。
    // JPushAdapter 走反射调用,依赖打开后无需改任何业务代码即可生效。
    // implementation(libs.jpush)
    // implementation(libs.jcore)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") version "2.2.20"
}

android {
    namespace = "com.mbzmhs.anidocktv"
    // 36:libvlc 3.7.3+ 的 AAR 要求 minCompileSdk=36(工具链已随其升级到 Kotlin 2.2/AGP 8.9)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mbzmhs.anidocktv"
        // 26:java.time(追番"最新更新"排序的时间解析)自 API 26 原生可用,再低需 core library desugaring
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // libvlc-all 自带 4 个 ABI(共 ~200MB so),只保留电视的 armeabi-v7a(单 ABI ~39MB)
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose for TV:TV 焦点/导航(标准 focusable 在 TV 上不可靠,必须用 focusModifier)
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.1.0")

    // Room:本地存储(追番/番剧缓存/剧集/下载任务);2.7 配 KSP2(Kotlin 2.2 工具链)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // 网络 + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 图片(Coil,Compose)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 内置播放器核心:libVLC Android 绑定稳定线最新(内核 3.0.x 最新,so 由 abiFilters 过滤)。
    // 3.7.7 的 AAR 要求 minCompileSdk=36 且依赖 kotlin-stdlib 2.2——工具链已随之升级
    // (Kotlin 2.2.20/AGP 8.9/Compose BOM 2025.09),运行端 minSdk 26 不受影响(安卓9 可用)。
    implementation("org.videolan.android:libvlc-all:3.7.7")
}

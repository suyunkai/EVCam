plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.kooo.evcam"
    compileSdk = 36

    // 绛惧悕閰嶇疆 (浣跨敤 AOSP 鍏叡娴嬭瘯绛惧悕)
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "android"
            keyAlias = "apkeasytool"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.kooo.evcam"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "0.9.9-test-01311425"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 浣跨敤绛惧悕閰嶇疆
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // 閽夐拤瀹樻柟 Stream SDK
    implementation("com.dingtalk.open:app-stream-client:1.3.12")

    // 飞书：使用轻量级 OkHttp WebSocket 实现，不再依赖官方 SDK

    // 网络请求和 WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON 瑙ｆ瀽
    implementation("com.google.code.gson:gson:2.10.1")

    // Glide 鍥剧墖鍔犺浇搴擄紙鐢ㄤ簬缂撳瓨鍜屼紭鍖栫缉鐣ュ浘鍔犺浇锛?
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // WorkManager 瀹氭椂浠诲姟锛堢敤浜庝繚娲伙級
    implementation("androidx.work:work-runtime:2.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

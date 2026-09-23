import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use(::load)
}

fun localString(name: String, default: String = ""): String =
    localProperties.getProperty(name, default)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.zszc.voicepipeline"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zszc.voicepipeline"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "IFLYTEK_APP_ID", "\"${localString("iflytek.appId")}\"")
        buildConfigField("String", "IFLYTEK_API_KEY", "\"${localString("iflytek.apiKey")}\"")
        buildConfigField("String", "IFLYTEK_API_SECRET", "\"${localString("iflytek.apiSecret")}\"")
        buildConfigField("String", "IFLYTEK_SPARK_API_PASSWORD", "\"${localString("iflytek.sparkApiPassword", localProperties.getProperty("spark.apiPassword", ""))}\"")
        buildConfigField("String", "IFLYTEK_SPARK_MODEL", "\"${localString("iflytek.sparkModel", localProperties.getProperty("spark.model", "generalv3.5"))}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("irf", "bin", "dat", "pcm")
    }
}

dependencies {
    implementation(files("libs/AIKit.aar"))
    implementation(files("libs/SparkChain.aar"))
    implementation("com.google.code.gson:gson:2.8.8")
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

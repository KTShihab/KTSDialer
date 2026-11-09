import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shihabkt.ktsdialer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihabkt.ktsdialer"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val properties = Properties()
                properties.load(keystorePropertiesFile.inputStream())
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

val releaseApkFileProvider = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val debugApkFileProvider = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")

val exportAndInstallApk = tasks.register("exportAndInstallApk") {
    description = "Copies release/debug APK to PC Downloads and installs on connected device"
    doLast {
        val relFile = releaseApkFileProvider.get().asFile
        val dbgFile = debugApkFileProvider.get().asFile
        val apkFile = if (relFile.exists()) relFile else dbgFile

        if (!apkFile.exists()) {
            println("No APK file found at: ${apkFile.absolutePath}")
            return@doLast
        }

        val userHome = System.getProperty("user.home")
        val pcDownloadsDir = File(userHome, "Downloads")
        if (!pcDownloadsDir.exists()) pcDownloadsDir.mkdirs()
        val pcTargetApk = File(pcDownloadsDir, "KTSDialer.apk")
        apkFile.copyTo(pcTargetApk, overwrite = true)
        println("Saved certified APK to PC Downloads: ${pcTargetApk.absolutePath} (${pcTargetApk.length()} bytes)")

        val adbPath = File(System.getenv("LOCALAPPDATA") ?: "$userHome\\AppData\\Local", "Android\\Sdk\\platform-tools\\adb.exe")
        if (adbPath.exists()) {
            try {
                val installProc = ProcessBuilder(adbPath.absolutePath, "install", "-r", "-d", apkFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val installOutput = installProc.inputStream.bufferedReader().readText()
                installProc.waitFor()
                println("Install output: $installOutput")
            } catch (e: Exception) {
                println("ADB install skipped or failed: ${e.message}")
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    finalizedBy(exportAndInstallApk)
}

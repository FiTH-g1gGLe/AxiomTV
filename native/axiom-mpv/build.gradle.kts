import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
}

val versionLong = "axiomtv"

android {
    namespace = "com.axiomtv.mpv"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "armeabi-v7a"
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
        consumerProguardFile("consumer-rules.pro")
        buildConfigField("String", "VERSION", "\"$versionLong\"")
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            languageVersion = KotlinVersion.KOTLIN_2_3
            jvmTarget = JvmTarget.JVM_11
            javaParameters = true
        }
    }
    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

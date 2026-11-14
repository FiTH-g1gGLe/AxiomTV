import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.plugin.serialization)
}

val isCI = providers.environmentVariable("CI").orElse("false").map { it.toBoolean() }
val shouldSign =
    isCI.zip(
        providers.environmentVariable("KEY_ALIAS").orElse("").map { it.isNotBlank() },
    ) { isCI, hasKey ->
        isCI && hasKey
    }
val ffmpegModuleExists =
    providers.provider { project.file("libs/lib-decoder-ffmpeg-release.aar").exists() }
val av1ModuleExists =
    providers.provider { project.file("libs/lib-decoder-av1-release.aar").exists() }
val mpvModuleExists =
    providers.provider { project.file("libs/wholphin-mpv-release.aar").exists() }
val extensionsRepoActive =
    providers.provider { project.hasProperty("WholphinExtensionsUsername") }

// See https://issuetracker.google.com/issues/402800800
val isBuildingBundle =
    providers.provider {
        gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    }

// AxiomTV versioning: every GitHub build gets a new version 0.1.<run number>,
// which the in-app updater compares against the latest GitHub release.
val buildNumber =
    providers
        .environmentVariable("GITHUB_RUN_NUMBER")
        .orElse("0")
        .map { it.toInt() }

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = JvmTarget.JVM_11
        javaParameters = true

        // Workaround for https://github.com/google/ksp/issues/2964
        moduleName = "${project.group}_${project.name}"
    }
}

private fun Provider<String>.getInt() = get().toInt()

configure<ApplicationExtension> {
    namespace = "com.axiomtv"
    compileSdk = libs.versions.compileSdk.getInt()

    defaultConfig {
        applicationId = "com.fithg1ggle.axiomtv"
        minSdk = libs.versions.minSdk.getInt()
        targetSdk = libs.versions.targetSdk.getInt()
        versionCode = 100 + buildNumber.get()
        versionName = "0.1.${buildNumber.get()}"
        testInstrumentationRunner = "com.github.damontecres.wholphin.test.WholphinTestRunner"

        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
        // TMDB key comes from the TMDB_API_KEY GitHub secret, never from the code
        val tmdbKey = providers.environmentVariable("TMDB_API_KEY").orElse("").get().trim()
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
    }

    signingConfigs {
        // Stable AxiomTV key so every new build installs over the last one.
        // Swap to the "ci" config (repo secrets) later for a private key.
        create("axiom") {
            storeFile = file("axiomtv.keystore")
            storePassword = "axiomtv-sideload"
            keyAlias = "axiomtv"
            keyPassword = "axiomtv-sideload"
            enableV1Signing = true
            enableV2Signing = true
        }
        if (shouldSign.get()) {
            create("ci") {
                file("ci.keystore").writeBytes(
                    Base64.getDecoder().decode(System.getenv("SIGNING_KEY")),
                )
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                storeFile = file("ci.keystore")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            if (shouldSign.get()) {
                signingConfig = signingConfigs.getByName("ci")
            } else {
                signingConfig = signingConfigs.getByName("axiom")
                val localPropertiesFile = project.rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    val properties = Properties()
                    properties.load(localPropertiesFile.inputStream())
                    val signingConfigName = properties["release.signing.config"]?.toString()
                    if (signingConfigName != null) {
                        signingConfig = signingConfigs.getByName(signingConfigName)
                    }
                }
            }
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    flavorDimensions += "version"
    productFlavors {
        val featureLeanback = "leanback"
        val featureUpdate = "UPDATING_ENABLED"
        val featureDiscover = "DISCOVER_ENABLED"

        fun ProductFlavor.setFeatureFlag(
            name: String,
            enabled: Boolean,
        ) {
            this.buildConfigField("boolean", name, "Boolean.parseBoolean(\"${enabled}\")")
        }
        create("default") {
            dimension = "version"
            isDefault = true
            manifestPlaceholders += mapOf(featureLeanback to false)
            setFeatureFlag(featureUpdate, true)
            setFeatureFlag(featureDiscover, true)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    splits {
        abi {
            // Disable split abis when building bundles
            isEnable = !isBuildingBundle.get()

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // Work around because libass-android & wholphin-mpv both (incorrectly) package libc++_shared.so
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    lint {
        disable.add("MissingTranslation")
    }
    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs
            .map { it as com.android.build.api.variant.impl.VariantOutputImpl }
            .forEach { output ->
                val abi =
                    output
                        .getFilter(FilterConfiguration.FilterType.ABI)
                        .let { if (it != null) "-${it.identifier}" else "" }
                val outputFileName =
                    "AxiomTV-${variant.flavorName}-${variant.buildType}$abi.apk"
                output.outputFileName = outputFileName
            }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)

    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.cachecontrol)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)


    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    implementation(libs.androidx.room.common.jvm)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.palette.ktx)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)

    implementation(libs.timber)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    if (ffmpegModuleExists.get()) {
        logger.info("Using local ffmpeg decoder")
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt ffmpeg decoder")
        implementation(libs.wholphin.extensions.ffmpeg)
    } else {
        logger.warn("Media3 ffmpeg decoder was NOT found")
    }
    if (av1ModuleExists.get()) {
        logger.info("Using local av1 decoder")
        implementation(files("libs/lib-decoder-av1-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt av1 decoder")
        implementation(libs.wholphin.extensions.av1)
    } else {
        logger.warn("Media3 av1 decoder was NOT found")
    }
    if (mpvModuleExists.get()) {
        logger.info("Using local libMPV build")
        implementation(files("libs/wholphin-mpv-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt libMPV")
        implementation(libs.wholphin.extensions.mpv)
    } else {
        logger.warn("libMPV was NOT found, using stub library")
        implementation(project(":wholphin-mpv-stub"))
    }

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = providers.gradleProperty("appVersionName").get()
val appVersionCode = providers.gradleProperty("appVersionCode").get()
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val targetAbis = providers.gradleProperty("targetAbi").orNull
    ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)

android {
    namespace = "com.storage.redirect.x"
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "com.storage.redirect.x"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode.toInt()
        versionName = appVersionName
        buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
        ndk {
            abiFilters += targetAbis?.takeIf { it.isNotEmpty() } ?: listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets.getByName("main").java.directories.add("../../srx_core/java_src/org/srx/packages")
    sourceSets.getByName("test").java.directories.addAll(listOf(
        "../../srx_core/java_src/org/srx/hook",
        "../../srx_core/java_src/org/srx/monitor",
    ))

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    androidResources { generateLocaleConfig = true }
    bundle { language { enableSplit = false } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        dex { useLegacyPackaging = true }
        resources {
            excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            isDebuggable = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.coroutines)
    implementation(libs.immutable)
    implementation(libs.gson)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1024m"
    maxParallelForks = 1
    // Compose dispatchers and native windows must not span Robolectric test-class sandboxes.
    forkEvery = 1
    jvmArgs("-XX:+UseSerialGC")
}

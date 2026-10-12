import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// The Geospatial API key. It comes from the machine (local.properties, gitignored, or the
// CAELIS_GEOSPATIAL_API_KEY environment variable of a CI job) and is never in the repository. A build
// without it is a complete, working build: the Geospatial switch is simply not offered
// (BuildConfig.HAS_GEOSPATIAL_API_KEY is false) and ArCoreSessionController never requests Geospatial.
//
// A key in a manifest is readable by anyone who unpacks the APK, so restrict it in the Google Cloud
// console to this package name and the signing certificate and to the ARCore API alone.
val geospatialApiKey: String = run {
    val localProperties = Properties()
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { localProperties.load(it) }
    (localProperties.getProperty("geospatial.api.key") ?: System.getenv("CAELIS_GEOSPATIAL_API_KEY") ?: "").trim()
}

android {
    namespace = "com.dazix.caelis"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.dazix.caelis"
        minSdk = 33
        targetSdk = 37
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["geospatialApiKey"] = geospatialApiKey
        buildConfigField("boolean", "HAS_GEOSPATIAL_API_KEY", geospatialApiKey.isNotEmpty().toString())
    }

    signingConfigs {
        val keystorePath = System.getenv("SIGNING_KEY_STORE_PATH")
        val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        val keyPassword = System.getenv("SIGNING_KEY_PASSWORD")

        if (!keystorePath.isNullOrBlank() &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank() &&
            file(keystorePath).exists()
        ) {
            create("release") {
                this.storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Filament materials: app/src/main/materials/*.mat -> generated assets/*.filamat, compiled by the
// matc of exactly the Filament version the app links against (see CompileFilamentMaterialsTask).
val compileFilamentMaterials = tasks.register<CompileFilamentMaterialsTask>("compileFilamentMaterials") {
    filamentVersion.set(libs.versions.filament)
    checksumFile.set(rootProject.layout.projectDirectory.file("gradle/filament-tools.sha256"))
    materialSources.from(layout.projectDirectory.dir("src/main/materials").asFileTree.matching { include("*.mat") })
    backends.set("opengl")
    toolsCacheDir.set(File(gradle.gradleUserHomeDir, "caches/caelis-filament-tools"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileFilamentMaterials,
            CompileFilamentMaterialsTask::outputDir,
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.flatbuffers.java)
    implementation(libs.filament.android)
    // Consent-gated at runtime (ArCoreSessionController); `optional` in the manifest.
    implementation(libs.arcore)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}


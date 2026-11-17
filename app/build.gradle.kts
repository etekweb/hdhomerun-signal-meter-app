import org.gradle.api.tasks.testing.Test
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "net.etekweb.hdhrmeter"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.etekweb.hdhrmeter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("production") {
            storeFile = rootProject.file(
                localProperties.getProperty("signing.storeFile")
                    ?: "${System.getProperty("user.home")}/.gradle/AndroidAppKeystore",
            )
            keyAlias = localProperties.getProperty("signing.keyAlias", "key0")
            storePassword = localProperties.getProperty("signing.storePassword")
            keyPassword = localProperties.getProperty("signing.keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("production")
        }
        release {
            signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("org.videolan.android:libvlc-all:3.7.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

// Android's unit-test stub jar also contains org.json, and those stubs throw.
// Putting the real library first makes the protocol tests parse JSON.
tasks.withType<Test>().configureEach {
    doFirst {
        val ordered = classpath.files.toList()
        val jsonJars = ordered.filter { it.name.startsWith("json-") && it.extension == "jar" }
        if (jsonJars.isNotEmpty()) {
            classpath = files(jsonJars + ordered.filter { it !in jsonJars })
        }
    }
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val oauthProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
fun oauthBuildString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

android {
    namespace = "es.robertodorado.robgit"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.robertodorado.robgit"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
        buildConfigField("String", "ROBGIT_GITHUB_CLIENT_ID", oauthBuildString(oauthProperties.getProperty("ROBGIT_GITHUB_CLIENT_ID", "")))
        buildConfigField("String", "ROBGIT_GITHUB_CLIENT_SECRET", oauthBuildString(oauthProperties.getProperty("ROBGIT_GITHUB_CLIENT_SECRET", "")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

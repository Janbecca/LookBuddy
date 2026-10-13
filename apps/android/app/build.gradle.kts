import java.nio.file.Files

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lookbuddy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lookbuddy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://replace-before-release.invalid/\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.kotlin_module",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/ASL2.0",
        )
        jniLibs.useLegacyPackaging = false
        jniLibs.pickFirsts += listOf("lib/armeabi-v7a/*.so", "lib/arm64-v8a/*.so")
    }
}

configurations.all {
    resolutionStrategy {
        force("org.slf4j:slf4j-api:1.7.32")
        force("androidx.appcompat:appcompat:1.6.1")
        force("androidx.fragment:fragment:1.5.4")
        force("androidx.appcompat:appcompat-resources:1.6.1")
        force("androidx.lifecycle:lifecycle-livedata-core:2.7.0")
        force("androidx.profileinstaller:profileinstaller:1.3.1")
        force("io.insert-koin:koin-core:4.1.1")
        force("com.squareup.okhttp3:okhttp:4.12.0")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(fileTree("libs") { include("*.aar") })

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ESIotKit 2.2.7 fat-AAR runtime dependencies, aligned with the organizer demo.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.kotlinx:atomicfu:0.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("com.squareup.okio:okio:3.16.4")
    implementation("io.ktor:ktor-network:3.3.3")
    implementation("io.ktor:ktor-client-core:3.3.3")
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    implementation("io.ktor:ktor-websockets:3.3.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
    implementation("io.ktor:ktor-client-logging:3.3.3")
    implementation("io.insert-koin:koin-core:4.1.1")
    implementation("com.benasher44:uuid:0.7.1")
    implementation("com.amazonaws:aws-android-sdk-iot:2.81.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("dev.whyoleg.cryptography:cryptography-core:0.5.0")
    implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.5.0")
    implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.5.0")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("de.jensklingenberg.ktorfit:ktorfit-lib:2.7.1")
    implementation("io.github.aakira:napier:2.7.1")
    implementation("co.touchlab:stately-common:2.0.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-core-okio:1.1.1")
    implementation("androidx.room:room-runtime:2.7.0-alpha08")
    implementation("androidx.room:room-ktx:2.7.0-alpha08")
    implementation("app.cash.sqldelight:android-driver:2.2.1")
    implementation("app.cash.sqldelight:runtime:2.2.1")
    implementation("app.cash.sqldelight:primitive-adapters:2.2.1")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.21")
    implementation("javax.inject:javax.inject:1")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// JDK 17's Windows launcher can decode Gradle UTF-8 @argfile paths with the
// system codepage before -Dfile.encoding applies. Stage only non-ASCII runtime
// entries into an ASCII temp path; never relocate or modify source files.
tasks.withType<Test>().configureEach {
    doFirst {
        if (System.getProperty("os.name").startsWith("Windows") && classpath.files.any { file -> file.absolutePath.any { it.code > 127 } }) {
            val stage = Files.createTempDirectory("lookbuddy-jvm-").toFile()
            classpath = files(classpath.files.mapIndexed { index, entry ->
                if (entry.absolutePath.all { it.code <= 127 }) entry else {
                    val target = stage.resolve(if (entry.isFile) "$index.jar" else "$index")
                    if (entry.isFile) entry.copyTo(target) else copy { from(entry); into(target) }
                    target
                }
            })
        }
    }
}


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystoreFile = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }
val releaseKeystorePath = releaseKeystoreFile?.let(::file)?.canonicalFile
if (releaseKeystorePath != null) {
    require(!releaseKeystorePath.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "RELEASE_KEYSTORE_FILE must point outside the repository"
    }
}

/** ABIs that have a complete set of bundled native executables checked in. */
val availableNativeAbis: Set<String> =
    file("src/main/jniLibs").listFiles()
        ?.filter { it.isDirectory }
        ?.filter { abiDir ->
            listOf("libxray.so", "libtun2socks.so", "libciadpi.so", "libtgwsproxy.so")
                .all { File(abiDir, it).isFile }
        }
        ?.map { it.name }
        ?.toSet()
        .orEmpty()

android {
    namespace = "com.fife.sa05"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.fife.sa05"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.environmentVariable("VERSION_CODE")
            .map(String::toInt)
            .orElse(1)
            .get()
        versionName = providers.environmentVariable("VERSION_NAME")
            .orElse("1.0")
            .get()
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            // Emulators are x86_64; release stays arm64-only. Only the ABIs whose native
            // libraries are actually present are added, so a checkout without an x86_64
            // build still produces a working arm64 dev APK instead of one that installs
            // on an emulator and dies looking for libxray.so.
            ndk {
                abiFilters += availableNativeAbis - "arm64-v8a"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.create("release").apply {
                    storeFile = releaseKeystorePath
                    storePassword = releaseKeystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
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
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val checkRepositorySecrets = tasks.register<Exec>("checkRepositorySecrets") {
    group = "verification"
    description = "Fails when Android secrets or local configuration are tracked by Git"
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        project.layout.projectDirectory.file("scripts/check-repository-secrets.sh").asFile,
        rootProject.projectDir
    )
}

tasks.named("preBuild") {
    dependsOn(checkRepositorySecrets)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // QR encoding for LAN sharing. Pure Java, no Android dependencies.
    implementation("com.google.zxing:core:3.5.4")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

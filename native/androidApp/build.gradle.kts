import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.baselineprofile)
}

// The version lives in gradle.properties (CI writes the release version there); the code is derived
// so the two can't drift. 1.2.3 -> 1020399. Prereleases sort below their release and dev below rc:
// 1.2.3-dev.4 -> 1020304, 1.2.3-rc.2 -> 1020352. The same rule is in iosApp/project.yml.
val appVersionName: String = providers.gradleProperty("vectra.versionName").get()
val appVersionCode: Int = run {
    val match = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(dev|rc)\.(\d+))?""").matchEntire(appVersionName)
        ?: error("vectra.versionName must be major.minor.patch[-dev.N|-rc.N] with minor and patch below 100, got '$appVersionName'")
    val (major, minor, patch) = match.groupValues.subList(1, 4).map(String::toInt)
    val pre = match.groupValues[5].toIntOrNull() ?: 0
    val build = when (match.groupValues[4]) {
        "dev" -> pre.coerceAtMost(49)
        "rc" -> 50 + pre.coerceAtMost(48)
        else -> 99
    }
    (major * 10_000 + minor * 100 + patch) * 100 + build
}

android {
    namespace = "dev.southstar21.vectra.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.southstar21.vectra"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // PocketBase backend; override with -Pvectra.pocketbaseUrl=…
        buildConfigField(
            "String",
            "POCKETBASE_URL",
            "\"${providers.gradleProperty("vectra.pocketbaseUrl").getOrElse("https://apivectra.southstar21.dev")}\"",
        )
        // Where the transit catalog is served: <origin>/catalog/*.json.
        buildConfigField(
            "String",
            "CATALOG_ORIGIN",
            "\"${providers.gradleProperty("vectra.catalogOrigin").getOrElse("https://vectra.southstar21.dev")}\"",
        )
    }

    // Release signing comes from -Pvectra.keystore=… (+ storePassword/keyAlias/keyPassword). Without one a
    // local release build uses the debug key so it installs; on CI or with -Pvectra.distribution=true the
    // verifyReleaseSigning task below fails the build instead.
    val keystore = providers.gradleProperty("vectra.keystore").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("vectra.storePassword").get()
                keyAlias = providers.gradleProperty("vectra.keyAlias").get()
                keyPassword = providers.gradleProperty("vectra.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // Phones get per-ABI code two ways: Play splits the bundle by ABI itself, and sideloads use the
    // arm64 APK. The universal APK carries every ABI for local installs on anything else.
    // Off for bundle builds: AGP can't bundle shrunk resources from multiple APKs (issuetracker 402800800),
    // so run bundleRelease on its own, not in one invocation with assembleRelease.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    // AGP's Compose Preview tasks refuse to register without this, which breaks `gradlew tasks --all`.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        baseline = file("lint-baseline.xml")
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
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors = true }
}

// The baseline profile plugin turns unit tests off for its own variants, and AGP's Compose Preview tasks
// fail on a variant without them; there are no tests in this module, so this only keeps task listing working.
androidComponents {
    beforeVariants { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

baselineProfile {
    // Generated on demand (:androidApp:generateBaselineProfile) and checked in, not rebuilt on every release.
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

// Only release variants hook this, so debug builds and lint on CI don't need signing secrets.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    val hasKeystore = providers.gradleProperty("vectra.keystore").isPresent
    val mustBeSigned = providers.environmentVariable("CI").isPresent ||
        providers.gradleProperty("vectra.distribution").map(String::toBoolean).getOrElse(false)
    doLast {
        check(hasKeystore || !mustBeSigned) {
            "Release build without a keystore: pass -Pvectra.keystore=… with vectra.storePassword, vectra.keyAlias " +
                "and vectra.keyPassword. The debug-key fallback is for local installs only."
        }
        if (!hasKeystore) logger.warn("Release build signed with the debug key (local install only).")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(projects.shared)
    baselineProfile(projects.baselineprofile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.compose.ui)
}

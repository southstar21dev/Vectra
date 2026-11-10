plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.detekt)
}

// Debug builds are versioned from git: the newest release tag plus the commit, e.g. 1.8.2+92e0b25.
// vectra.versionName in gradle.properties is only current while CI builds a release (it writes it
// then and never commits it), so a local build reading it would show a long-gone version. Release
// builds keep using vectra.versionName; without tags (a shallow clone) debug builds fall back to it.
// iosApp/project.yml applies the same rule to the Debug configuration's Info.plist.
val latestReleaseTag: Provider<String> = providers.exec {
    commandLine("git", "tag", "--list", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { out ->
    out.lines()
        .mapNotNull { Regex("""v(\d+)\.(\d+)\.(\d+)""").matchEntire(it.trim())?.groupValues?.drop(1)?.map(String::toInt) }
        .maxWithOrNull(compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] }))
        ?.joinToString(".")
}
val gitCommit: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { null } }

/** The x.y.z a debug build is based on: the newest release tag, else vectra.versionName. */
val debugBaseVersion by extra(latestReleaseTag.orElse(providers.gradleProperty("vectra.versionName")))

/** What a debug build shows: [debugBaseVersion] plus the commit. */
val debugVersionName by extra(
    debugBaseVersion.zip(gitCommit.orElse("")) { version, commit -> if (commit.isEmpty()) version else "$version+$commit" },
)

// One run over all modules' sources: `./gradlew detekt`; `detektBaseline` regenerates the baseline.
detekt {
    source.setFrom("shared/src", "androidApp/src", "baselineprofile/src")
    config.setFrom("config/detekt/detekt.yml")
    baseline = file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
}

dependencies {
    detektPlugins(libs.compose.rules.detekt)
}

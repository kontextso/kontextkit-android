plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Maven coordinates published to Maven Central as
// `so.kontext.kit:kontext-kit-android-om:<kitVersion>`: IAB Open Measurement
// on top of the kit, released with it under the same version. Only the SDKs
// that measure with OMID depend on it.
//
// The published version is `libs.versions.kit` (gradle/libs.versions.toml)
// unless overridden by `-PkitVersion=X.Y.Z` at publish time (CI does this
// from the git tag — see .github/workflows/publish.yml).
val kitVersion = providers.gradleProperty("kitVersion").orElse(libs.versions.kit.get())
group = "so.kontext.kit"
version = kitVersion.get()

android {
    namespace = "so.kontext.kit.om"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// Strict API mode — every top-level / class member must spell out its
// visibility (`public` / `internal` / `private`) and public functions need
// an explicit return type. Catches accidentally-public helpers before they
// become committed surface area on the published artifact.
kotlin {
    explicitApi()
}

dependencies {
    // The kit's types (Context helpers) are part of this module's API.
    api(project(":"))
    // OmManager.createSession is suspend and runs its WebView work on the main dispatcher.
    implementation(libs.kotlinx.coroutines.android)
    // IAB OMID Android. `OmManager` accesses OMID purely via reflection,
    // but the classes have to be on the runtime classpath of the host app
    // for the reflective loader to succeed (otherwise OMID degrades to a
    // no-op session lifecycle). As `implementation`, the IAB coordinate is
    // in the published POM at `runtime` scope: AGP merges the AAR into the
    // APK without exposing OMID's API on the consumer's compile classpath.
    // `:omsdk-android` republishes the unmodified IAB AAR as
    // `so.kontext.iab:omsdk-android:1.6.4` (see its build.gradle.kts).
    implementation(project(":omsdk-android"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Vanniktech maven-publish — same pattern as v3 sdk-kotlin (`:ads`).
// Activates publishing to Maven Central with auto-release + signing.
// Credentials come from Gradle properties at publish time.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Local `publishToMavenLocal` runs without GPG creds, so don't enable
    // signing then. Maven Central enforces signatures on upload — CI sets
    // `signingInMemoryKey` env which flips this branch on.
    val hasSigningKey = (findProperty("signingInMemoryKey") as String?) != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    if (hasSigningKey) {
        signAllPublications()
    }

    coordinates(
        groupId = group.toString(),
        artifactId = "kontext-kit-android-om",
        version = version.toString(),
    )

    pom {
        name.set("Kontext Kit Android: Open Measurement")
        description.set("IAB Open Measurement (OMID) for the Kontext Android SDKs, on top of Kontext Kit Android.")
        url.set("https://www.kontext.so/advertisers")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("kontext")
                name.set("kontext")
            }
        }

        scm {
            url.set("https://github.com/kontextso/kontextkit-android")
            connection.set("scm:git:https://github.com/kontextso/kontextkit-android.git")
            developerConnection.set("scm:git:ssh://github.com/kontextso/kontextkit-android.git")
        }
    }
}

// Lint / style — same versions as sdk-kotlin so contributors don't have
// two ktlint/detekt baselines to chase.
spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        targetExclude("**/build/**/*.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
}

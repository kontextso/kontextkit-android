plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Maven coordinates published to Maven Central as
// `so.kontext.kit:kontext-kit-android:<kitVersion>`. Consumed by
// sdk-kotlin, sdk-react-native (Android), and sdk-flutter (Android).
//
// The published version is `libs.versions.kit` (gradle/libs.versions.toml)
// unless overridden by `-PkitVersion=X.Y.Z` at publish time (CI does this
// from the git tag — see .github/workflows/publish.yml).
val kitVersion = providers.gradleProperty("kitVersion").orElse(libs.versions.kit.get())
group = "so.kontext.kit"
version = kitVersion.get()

android {
    namespace = "so.kontext.kit"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
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
    // Required by InAppBrowserManager (Chrome Custom Tabs)
    implementation(libs.androidx.browser)
    // Required by TCFDataProvider (PreferenceManager)
    implementation(libs.androidx.preference.ktx)
    // Required by AdvertisingIdProvider (GAID via Play Services)
    implementation(libs.play.services.ads.identifier)

    // OmManager accesses the IAB OMID SDK entirely via reflection at
    // runtime (the AAR is bundled in local-maven/ next to this build file
    // and the host app provides it). Not declared as a build dep here
    // because AAR transform fails in CI unit tests; consumers wire the AAR.

    // Tests use JUnit 4 + Robolectric (matches v3 sdk-kotlin's :ads test stack).
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
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "kontext-kit-android",
        version = version.toString(),
    )

    pom {
        name.set("Kontext Kit Android")
        description.set("Shared Android primitives (device info, privacy, in-app browser, OMID) for the Kontext Kotlin / React Native / Flutter SDKs.")
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

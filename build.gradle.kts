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

    // IAB OMID Android — `OmManager` accesses OMID purely via reflection,
    // but the classes have to be on the runtime classpath of the host app
    // for the reflective loader to succeed (otherwise OMID gracefully
    // degrades to no-op session lifecycle, killing measurement coverage).
    // Declaring as `implementation` here means the IAB coordinate ends up
    // in our published POM at `runtime` scope, so AGP merges the AAR into
    // the final APK without exposing OMID's API surface on the consumer's
    // compile classpath. Same UX as kontextkit-ios shipping the OMSDK
    // xcframework inside the podspec.
    //
    // The `:omsdk-android` subproject republishes the unmodified IAB AAR
    // as `so.kontext.iab:omsdk-android:1.6.4` (see its build.gradle.kts).
    // Vanniktech maven-publish translates this `project(...)` dep to the
    // Maven coordinate in the published POM.
    implementation(project(":omsdk-android"))

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

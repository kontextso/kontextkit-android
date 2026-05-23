// :omsdk-android — thin redistribution module for the IAB Open Measurement
// SDK Android AAR. IAB Tech Lab distributes the AAR off Maven Central via
// their compliance portal, so every consumer of the Kontext SDKs would
// otherwise have to vendor the AAR locally to resolve `iab:omsdk-android`
// at build time. This module republishes the unmodified AAR under our own
// `so.kontext.iab` group ID so kontextkit-android consumers get the OMID
// classes transitively from Maven Central — same UX as kontextkit-ios
// shipping the OMSDK xcframework inside the KontextKit podspec.
//
// Updating IAB OMID: drop the new AAR next to this build file as
// `omsdk-android-<version>.aar`, bump `iabOmsdkVersion` below, and bump
// the consumer dep version in kontextkit-android's main module.
//
// Redistribution complies with OM License v1.1 Section 4: AAR is shipped
// unmodified in Object form, and the LICENSE text is bundled alongside
// the artifact via `pom { licenses { ... } }` + the LICENSE file in this
// directory.
//
// Why `java-library` for an Android AAR module: vanniktech maven-publish
// requires one of its supported "Platform" plugins to register the
// `publishToMavenCentral` task. We have no source to compile — the
// publication is just the IAB AAR — but applying `java-library` is the
// cheapest way to satisfy vanniktech's plugin-detection. We replace its
// default jar artifact with the AAR via `afterEvaluate` below, and the
// resulting `kontext-iab/omsdk-android-1.6.4.aar` upload bundle matches
// what we'd ship from a proper android-library module.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

val iabOmsdkVersion = "1.6.4"
val iabOmsdkAar = layout.projectDirectory.file("omsdk-android-$iabOmsdkVersion.aar")

group = "so.kontext.iab"
version = iabOmsdkVersion

mavenPublishing {
    // `JavaLibrary` is the vanniktech Platform that gives us
    // `publishToMavenCentral` + signing + Central Portal bundling. We
    // configure no real javadoc/sources jars because there's no Kotlin/Java
    // source — `JavadocJar.Empty()` produces an empty file just to satisfy
    // Maven Central's "must have -javadoc.jar" requirement, and
    // `sourcesJar = false` skips the sources artifact entirely.
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = false))
    publishToMavenCentral(automaticRelease = true)
    // `publishToMavenLocal` during development has no signing key set up;
    // only sign when CI exports the in-memory PGP creds. Maven Central
    // enforces signatures on upload — CI handles that path.
    val hasSigningKey = (findProperty("signingInMemoryKey") as String?) != null ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    if (hasSigningKey) {
        signAllPublications()
    }
    coordinates("so.kontext.iab", "omsdk-android", iabOmsdkVersion)

    pom {
        name.set("IAB OMID Android (Kontext redistribution)")
        description.set(
            "Unmodified redistribution of the IAB Tech Lab Open Measurement SDK " +
                "Android AAR (omsdk-android-$iabOmsdkVersion) under the " +
                "so.kontext.iab group ID. IAB Tech Lab distributes the original " +
                "AAR off Maven Central via their compliance portal; this artifact " +
                "republishes the unmodified AAR under our coordinate so consumers " +
                "of so.kontext.kit:kontext-kit-android can resolve OMID transitively " +
                "from Maven Central.",
        )
        url.set("https://github.com/kontextso/kontextkit-android")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Open Measurement (OM) License for Native-App Measurement, V 1.1")
                url.set(
                    "https://iabtechlab.com/wp-content/uploads/2022/04/" +
                        "IAB_Tech_Lab_Open_Measurement_Native_License_Final.pdf",
                )
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("kontext")
                name.set("Kontext")
                email.set("support@kontext.so")
            }
        }

        scm {
            url.set("https://github.com/kontextso/kontextkit-android")
            connection.set("scm:git:https://github.com/kontextso/kontextkit-android.git")
            developerConnection.set("scm:git:ssh://github.com/kontextso/kontextkit-android.git")
        }
    }
}

// Gradle module metadata describes variants with concrete file references
// computed from the `java-library` source set. We swap the jar artifact
// for an AAR at publication time, which makes those references stale —
// the published `.module` JSON would point at `omsdk-android-1.6.4.jar`
// but we'd actually upload `omsdk-android-1.6.4.aar`. Disable module
// metadata generation entirely; downstream consumers fall back to the
// POM, which only declares the AAR artifact and packaging.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

// --- Maven Central publish gate -------------------------------------------
// The IAB OMID AAR (1.6.4) is immutable on Maven Central once uploaded, and
// its version is independent of KontextKit's semver. Normal KontextKit
// releases must NOT re-upload it: the Central Portal rejects an
// already-existing coordinate and fails the *entire aggregated deployment*
// (vanniktech bundles every module in one Gradle invocation into a single
// deployment). That is exactly what broke the 0.0.7 release — the bundle
// paired the new `kontext-kit-android:0.0.7` with the already-published
// `omsdk-android:1.6.4`, so neither published.
//
// Publish this module only when the AAR itself changes, by passing
// `-PpublishOmsdk=true`. Otherwise its Maven Central upload tasks are
// disabled, so a normal release deployment carries only the main module.
val publishOmsdk = (project.findProperty("publishOmsdk") as String?) == "true"
if (!publishOmsdk) {
    tasks.matching { it.name.contains("MavenCentral") }
        .configureEach { enabled = false }
}

// Swap vanniktech's default jar artifact for the IAB AAR. `afterEvaluate`
// because vanniktech registers its publication during `apply()` and we
// need to mutate it after that registration completes.
afterEvaluate {
    publishing.publications.named<MavenPublication>("maven") {
        // Drop the empty .jar that `java-library` produced (classifier ==
        // null, extension == "jar"). The javadoc jar (-javadoc.jar) stays
        // — Maven Central requires a `-javadoc.jar` to be present, but
        // it's allowed to be empty (`JavadocJar.Empty()` above).
        val empty = artifacts.find { it.classifier == null && it.extension == "jar" }
        if (empty != null) artifacts.remove(empty)
        artifact(iabOmsdkAar) {
            extension = "aar"
        }
        pom.packaging = "aar"
    }
}

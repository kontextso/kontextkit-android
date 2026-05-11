# Releasing

- This document describes the process for cutting a new release of **KontextKit (Android)**.
- Follow these steps to ensure consistency across releases.
- Replace version `1.0.0` with the proper one instead.

> We use versioning without `v` at the front. For example: `1.0.0`.

---

## 1. Create a release branch and test

1. Checkout branch `main`.
2. Pull the latest changes.
3. Create a new branch `release/1.0.0`.
4. Make sure it builds and tests pass:
   ```bash
   ./gradlew build test
   ```
5. Make sure lint is clean:
   ```bash
   ./gradlew spotlessCheck detekt
   ```

## 2. Update the changelog

Edit `CHANGELOG.md` to include the new release notes at the top.

Standard release:
```markdown
## 1.0.0
* Add new provider.
* Fix some bug.
* Remove old API.
```

If the release contains breaking changes, add a `### Breaking` section before the bullet points:
```markdown
## 2.0.0
### Breaking
Short description of what changed and what integrators need to do.

* Add new feature.
* Fix some bug.
```

## 3. Update the kit version

Update `kit` in `gradle/libs.versions.toml`:

```toml
[versions]
kit = "1.0.0"
```

## 4. Commit changes

```bash
git add CHANGELOG.md gradle/libs.versions.toml
git commit -m "Prepare release 1.0.0"
```

## 5. Open pull request

1. Create a PR to `main` named: "Release version 1.0.0" and use the latest changelog entry as the PR description.
2. Merge the PR to `main`.

## 6. Create an annotated tag

The publish workflow triggers on plain semver tags.

```bash
git checkout main
git pull
git tag -a 1.0.0 -m "Release 1.0.0"
git push origin 1.0.0
```

## 7. Publish to Maven Central

Pushing the tag triggers `.github/workflows/publish.yml`, which:
1. Extracts the version from the tag.
2. Runs `./gradlew publishToMavenCentral -PkitVersion=<tag>` with credentials from repo secrets.
3. Signs all publications with the configured GPG key.
4. Auto-releases to Maven Central (via Vanniktech's `publishToMavenCentral(automaticRelease = true)`).

Required repo secrets (Settings → Secrets and variables → Actions):
- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY` — GPG private key, ASCII-armored, single line
- `SIGNING_PASSWORD` — passphrase for the signing key

For a **manual** publish (e.g. dev / SNAPSHOT), populate `~/.gradle/gradle.properties` from `gradle.properties.example` and run:

```bash
./gradlew publishToMavenCentral -PkitVersion=1.0.0
```

## 8. Verify

1. Check the artifact at [search.maven.org](https://search.maven.org/artifact/so.kontext.kit/kontext-kit-android).
2. Maven Central propagation takes ~10–30 min.
3. Bump the consuming SDKs (sdk-kotlin, sdk-react-native, sdk-flutter) to the new KontextKit version and confirm they build.

## Bundled OMID AAR

KontextKit vendors `local-maven/iab/omsdk-android/1.6.4/omsdk-android-1.6.4.aar` directly in the repo. `OmManager` loads it via reflection at runtime — it is NOT declared as a Gradle dependency (the AAR transform fails in CI unit tests, and consumers wire the AAR in their own build to keep KontextKit Maven-installable without surfacing the IAB module).

Updating the AAR is a regular file replacement — drop the new AAR + POM in place, bump the IAB version referenced in `CHANGELOG.md`, and follow the normal release flow. The AAR version is independent of KontextKit's semver — bumping the AAR is a regular KontextKit minor/major bump per semver rules on the public Kotlin API.

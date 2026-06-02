# Releasing Defined

> **Status: prepared, not published.** Everything below is wired up and verified
> locally. The library ships when the team decides it's ready — nothing is uploaded
> to any public repository yet.

## How comparable FTC libraries distribute

We modeled this on how the community already consumes libraries:

| Library | Distribution |
|---|---|
| **Pedro Pathing** | Maven Central (`com.pedropathing:ftc`) + a self‑hosted Maven repo (`mymaven.bylazar.com`) |
| **FTCLib** | Maven Central / JitPack |
| **SolversLib, NextFTC** | Maven Central |

So teams add a `maven { ... }` repo and one `implementation` line. We target the
same experience with **two** ready paths:

1. **Maven Central** (the primary, "Pedro" path) — most discoverable.
2. **JitPack** (zero‑infra fallback) — builds straight from a Git tag.

## What's already configured

- `maven-publish` + `signing` applied to `defined-core`, `defined-ftc`, `defined-pedro`
  (see [`gradle/publishing.gradle`](../gradle/publishing.gradle)).
- Each module publishes **main + sources + javadoc + POM** with full license / SCM /
  developer metadata.
- Coordinates: `com.teamundefined:defined-core | defined-ftc | defined-pedro`.
- A **local staging** repository for dry runs.
- Signing only activates when a key is present, so dev builds stay friction‑free.

### Verify the artifacts locally (safe, publishes nothing)

```bash
./gradlew publishReleasePublicationToLocalStagingRepository
# inspect: defined-*/build/staging-repo/com/teamundefined/...
```

## Cutting a Maven Central release (when ready)

1. **Own the namespace.** Verify `com.teamundefined` on the
   [Sonatype Central Portal](https://central.sonatype.com) using the
   `teamundefined.com` domain (DNS TXT record). The group ID matches the domain you
   control, so this is straightforward.
2. **Create a GPG signing key** and publish the public key to a keyserver.
3. **Provide credentials** via `~/.gradle/gradle.properties` (never commit these):
   ```properties
   centralUsername=<portal token user>
   centralPassword=<portal token>
   signingKey=<ASCII‑armored private key>
   signingPassword=<key passphrase>
   ```
4. **Set the release version** (drop `-SNAPSHOT`) in `gradle.properties`:
   ```properties
   version=1.0.0
   ```
5. **Publish:**
   ```bash
   ./gradlew clean build
   ./gradlew publishReleasePublicationToCentralRepository
   ```
6. Finish the release in the Central Portal UI (validate → publish).
7. **Tag the release:** `git tag v1.0.0 && git push --tags`.
8. Bump back to the next `-SNAPSHOT` on `main`.

## JitPack fallback (works today, no infra)

JitPack builds each module from a Git tag on demand. Consumers add:

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    implementation 'com.github.team-undefined.defined:defined-core:v1.0.0'
}
```

The included [`jitpack.yml`](../jitpack.yml) pins the JDK so JitPack builds match local.

## Release checklist

- [ ] `./gradlew clean build` green (all modules, all tests)
- [ ] `CHANGELOG` / release notes written
- [ ] Version set (no `-SNAPSHOT`) in `gradle.properties`
- [ ] `./gradlew publishReleasePublicationToLocalStagingRepository` inspected
- [ ] Credentials + signing key present (Central path only)
- [ ] Publish, then verify a clean consumer project can resolve the artifacts
- [ ] Git tag pushed; version bumped to next `-SNAPSHOT`

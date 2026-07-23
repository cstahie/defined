# Self-hosting Defined on GitHub Pages

This is how we distribute Defined — the same idea Pedro Pathing uses (a Maven repo
served over HTTPS), but free and with zero servers. A Maven repository is just a
folder of files; we keep that folder at [`../maven-repo`](../maven-repo) and mirror
it to the `gh-pages` branch so anyone can read it.

## Cutting a release (the easy way)

Run one command:

```bash
./release.sh 0.2.0
```

That script sets the version, builds + tests everything, packages the artifacts into
`maven-repo/`, commits, tags `v0.2.0`, pushes to GitHub, and updates the `gh-pages`
Maven site. If a test fails, nothing is released.

> Releases are forever — the script refuses to overwrite a version that already
> exists. Always pick a new, higher number.

## One-time GitHub setup

The repo must be **public** — GitHub Pages from a private repo needs a paid plan,
and the published site is world-readable either way, so there's nothing to gain by
keeping it closed.

1. Push this project to GitHub (already done: `git@github.com:cstahie/defined.git`).
2. Make the repo public: **Settings → General → Change visibility → Public**.
3. Run `./release.sh 0.1.0` once (it creates the `gh-pages` branch for you).
4. On GitHub: **Settings → Pages → Build from branch → `gh-pages` / root**.
5. Wait ~1 minute. The Maven site is live at `https://cstahie.github.io/defined/`.

> Steps 3 and 4 are in that order on purpose — the Pages settings page won't let you
> pick `gh-pages` until the branch exists.

## How other teams use it

```gradle
repositories {
    maven { url 'https://cstahie.github.io/defined' }
}
dependencies {
    implementation 'com.teamundefined:defined-core:0.2.0'
    implementation 'com.teamundefined:defined-ftc:0.2.0'    // optional FTC glue
    implementation 'com.teamundefined:defined-pedro:0.2.0'  // optional Pedro actions
}
```

## How it works under the hood

- `./gradlew publishToPages` writes the standard Maven layout (jars/aars + `.pom` +
  `maven-metadata.xml` + checksums) into `maven-repo/`.
- `maven-repo/` is committed on the main branch and **accumulates every version** —
  never delete old folders.
- `release.sh` mirrors `maven-repo/` onto the `gh-pages` branch with
  `git subtree split`, so the site always matches the folder.
- No GPG signing, no Sonatype account, no DNS — unlike Maven Central. (If you later
  want the cleaner `search.maven.org` listing, see [RELEASING.md](RELEASING.md).)

## Testing a release locally first

You don't even need GitHub to verify consumption. Point a throwaway project's
repository at the local folder:

```gradle
repositories { maven { url uri('/path/to/library/maven-repo') } }
dependencies { implementation 'com.teamundefined:defined-core:0.2.0' }
```

(There's a ready-made example at `../defined-consumer-test`.)

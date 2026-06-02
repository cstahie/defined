#!/usr/bin/env bash
#
# 🤖 Defined — one-command release script (for Team 19112 students)
#
#   ./release.sh 0.2.0
#
# It does EVERYTHING:
#   1. sets the version
#   2. builds + runs all tests (stops if anything fails)
#   3. packages the library into ./maven-repo
#   4. commits + tags the release
#   5. pushes it to GitHub and updates the gh-pages Maven site
#
# After it finishes, other teams can use the new version immediately. 🎉
#
set -euo pipefail
cd "$(dirname "$0")"

# ----- pretty output helpers -----
say()  { printf "\n\033[1;36m%s\033[0m\n" "$*"; }   # cyan
ok()   { printf "\033[1;32m✔ %s\033[0m\n" "$*"; }   # green
warn() { printf "\033[1;33m⚠ %s\033[0m\n" "$*"; }   # yellow
die()  { printf "\033[1;31m✗ %s\033[0m\n" "$*" >&2; exit 1; }

# ----- 0. check the version argument -----
VERSION="${1:-}"
[ -n "$VERSION" ] || die "Usage: ./release.sh <version>   (example: ./release.sh 0.2.0)"
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  die "Version must look like 1.2.3 — numbers only, no 'v' and no '-SNAPSHOT'."
fi

# Refuse to re-release a version that already exists (releases are forever).
if [ -d "maven-repo/com/teamundefined/defined-core/$VERSION" ]; then
  die "Version $VERSION was already published. Pick a new, higher number."
fi

say "🤖 Releasing Defined v$VERSION"

# ----- 1. set the version in gradle.properties -----
say "1/5  Setting version to $VERSION"
sed -i.bak "s/^version=.*/version=$VERSION/" gradle.properties && rm -f gradle.properties.bak
ok "version=$VERSION"

# ----- 2. build + test (fails loudly if a test is red) -----
say "2/5  Building and testing everything (this can take a minute)"
./gradlew clean build || die "Build/tests failed — fix the errors above, nothing was released."
ok "All tests passed"

# ----- 3. package into the Maven repo folder -----
say "3/5  Packaging into ./maven-repo"
./gradlew publishToPages
ok "Artifacts written to maven-repo/com/teamundefined/"

# ----- 4. commit + tag -----
say "4/5  Saving the release in git"
git add gradle.properties maven-repo
git commit -q -m "release: v$VERSION" || warn "Nothing new to commit"
git tag "v$VERSION" 2>/dev/null || warn "Tag v$VERSION already exists"
ok "Committed and tagged v$VERSION"

# ----- 5. push to GitHub + update the gh-pages Maven site -----
if git remote get-url origin >/dev/null 2>&1; then
  say "5/5  Pushing to GitHub + updating the gh-pages Maven site"
  BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  git push origin "$BRANCH"
  git push origin "v$VERSION"

  # gh-pages = a snapshot of maven-repo/. maven-repo keeps ALL versions, so it's
  # safe to (re)build gh-pages from it each time.
  git branch -D gh-pages-tmp >/dev/null 2>&1 || true
  git subtree split --prefix maven-repo -b gh-pages-tmp >/dev/null
  git push -f origin gh-pages-tmp:gh-pages
  git branch -D gh-pages-tmp >/dev/null 2>&1 || true
  ok "Pushed. GitHub Pages will update in ~1 minute."

  # Work out the public URL from the git remote (best effort).
  URL="$(git remote get-url origin | sed -E 's#git@github.com:#https://github.com/#; s#\.git$##')"
  SLUG="$(printf '%s' "$URL" | sed -E 's#https://github.com/##')"
  ORG="${SLUG%%/*}"; REPO="${SLUG##*/}"
  PAGES="https://${ORG}.github.io/${REPO}"
  say "🎉 Released! Other teams add this to their build:"
  cat <<EOF

  repositories {
      maven { url '${PAGES}' }
  }
  dependencies {
      implementation 'com.teamundefined:defined-core:${VERSION}'
      implementation 'com.teamundefined:defined-ftc:${VERSION}'    // optional
      implementation 'com.teamundefined:defined-pedro:${VERSION}'  // optional
  }

  (One-time on GitHub: Settings → Pages → Branch = gh-pages, folder = / root.)
EOF
else
  say "5/5  No GitHub remote yet — skipping push (everything else is done!)"
  warn "When the repo exists, run:"
  echo "    git remote add origin https://github.com/<org>/<repo>.git"
  echo "    ./release.sh $VERSION   # run again to push + publish gh-pages"
fi

ok "Release v$VERSION complete."

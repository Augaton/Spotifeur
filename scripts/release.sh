#!/usr/bin/env bash
# Builds a signed release APK with the native engine and publishes it as a GitHub release.
#
# Usage:
#   scripts/release.sh --apkm /path/to/com.spotify.music_9.2.0.26.apkm [options]
#
# Options:
#   --apkm PATH       Official Spotify APKM 9.2.0.26. Optional if native-runtime/artifacts holds it
#                     or the extracted files (see native-runtime/README.md).
#   --notes FILE      Release notes in Markdown. Default: generated from the commits since the last tag.
#   --draft           Create the GitHub release as a draft.
#   --prerelease      Mark the GitHub release as a pre-release.
#   --dry-run         Build, sign and verify, but do not tag, push or publish.
#   --yes             Do not ask for confirmation before publishing.
#   -h, --help        Show this help.
#
# Signing (environment variables):
#   SPOTIFEUR_KEYSTORE           Keystore path. Default: ~/.spotifeur/release.jks (created if missing).
#   SPOTIFEUR_KEY_ALIAS          Key alias. Default: spotifeur.
#   SPOTIFEUR_KEYSTORE_PASSWORD  Keystore password. Asked if not set.
#   SPOTIFEUR_KEY_PASSWORD       Key password. Default: the keystore password.
#
# The version comes from versionName and versionCode in app/build.gradle.kts. The tag is v<versionName>.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

die() { echo "error: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

APKM=""; NOTES=""; DRAFT=0; PRERELEASE=0; DRY_RUN=0; YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apkm) APKM="${2:?--apkm needs a path}"; shift 2 ;;
    --notes) NOTES="${2:?--notes needs a file}"; shift 2 ;;
    --draft) DRAFT=1; shift ;;
    --prerelease) PRERELEASE=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes) YES=1; shift ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# --- Tools -------------------------------------------------------------------
step "Checking tools"
for tool in git gh keytool unzip shasum; do
  command -v "$tool" >/dev/null || die "$tool is not installed"
done
is_jdk21() { [[ -x "$1/bin/java" && "$("$1/bin/java" -version 2>&1)" == *'version "21'* ]]; }
if ! is_jdk21 "${JAVA_HOME:-/nonexistent}"; then
  # A missing or stale JAVA_HOME: look for an installed JDK 21.
  JAVA_HOME="$( { [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 2>/dev/null; } || true)"
  is_jdk21 "$JAVA_HOME" || die "JDK 21 is required (set JAVA_HOME to a JDK 21)"
fi
export JAVA_HOME

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then SDK="$(sed -n 's/^sdk\.dir=//p' local.properties)"; fi
[[ -d "$SDK" ]] || die "Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[[ -x "$APKSIGNER" && -x "$AAPT2" ]] || die "apksigner/aapt2 not found in $BUILD_TOOLS"

gh auth status >/dev/null 2>&1 || die "gh is not signed in (run: gh auth login)"

# --- Repository state ---------------------------------------------------------
step "Checking the repository"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" ]] || die "releases are made from main (current branch: $BRANCH)"
[[ -z "$(git status --porcelain)" ]] || die "the working tree has uncommitted changes"
git fetch --quiet origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main (pull or push first)"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts | head -n 1)"
[[ -n "$VERSION" && -n "$CODE" ]] || die "could not read versionName/versionCode from app/build.gradle.kts"
TAG="v$VERSION"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
  die "tag $TAG already exists: bump versionName and versionCode in app/build.gradle.kts first"
fi
REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
VISIBILITY="$(gh repo view --json visibility --jq .visibility)"
echo "Repository $REPO ($VISIBILITY), version $VERSION ($CODE), tag $TAG"

# --- Spotify engine ----------------------------------------------------------
GRADLE_ARGS=()
if [[ -n "$APKM" ]]; then
  [[ -f "$APKM" ]] || die "APKM not found: $APKM"
  GRADLE_ARGS+=("-PspotifyArtifact=$(cd "$(dirname "$APKM")" && pwd)/$(basename "$APKM")")
elif [[ ! -f native-runtime/artifacts/spotify-9.2.0.26.apkm ]] &&
     [[ ! -f native-runtime/artifacts/spotify-9.2.0.26.base.apk || ! -f native-runtime/artifacts/spotify-9.2.0.26.orbit.so ]]; then
  die "no Spotify engine: pass --apkm or see native-runtime/README.md"
fi

# --- Signing key -------------------------------------------------------------
step "Preparing the signing key"
KEYSTORE="${SPOTIFEUR_KEYSTORE:-$HOME/.spotifeur/release.jks}"
ALIAS="${SPOTIFEUR_KEY_ALIAS:-spotifeur}"
if [[ -z "${SPOTIFEUR_KEYSTORE_PASSWORD:-}" ]]; then
  read -r -s -p "Keystore password: " SPOTIFEUR_KEYSTORE_PASSWORD; echo
fi
[[ ${#SPOTIFEUR_KEYSTORE_PASSWORD} -ge 6 ]] || die "the keystore password must have at least 6 characters"
KEY_PASSWORD="${SPOTIFEUR_KEY_PASSWORD:-$SPOTIFEUR_KEYSTORE_PASSWORD}"

if [[ ! -f "$KEYSTORE" ]]; then
  echo "No keystore at $KEYSTORE."
  read -r -p "Create a new release key there? [y/N] " answer
  [[ "$answer" == [yY]* ]] || die "no keystore"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Spotifeur" \
    -storepass "$SPOTIFEUR_KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" >/dev/null
  chmod 600 "$KEYSTORE"
  echo "Created $KEYSTORE. Back it up: every future update must be signed with this key."
fi
keytool -list -keystore "$KEYSTORE" -storepass "$SPOTIFEUR_KEYSTORE_PASSWORD" -alias "$ALIAS" >/dev/null 2>&1 ||
  die "cannot open alias $ALIAS in $KEYSTORE (wrong password or alias)"

# --- Build -------------------------------------------------------------------
step "Running the unit tests and building the release APK"
./gradlew --no-daemon ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"} \
  -Pandroid.injected.signing.store.file="$KEYSTORE" \
  -Pandroid.injected.signing.store.password="$SPOTIFEUR_KEYSTORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias="$ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASSWORD" \
  :app:clean :app:testDebugUnitTest :app:assembleRelease

BUILT="$(ls app/build/outputs/apk/release/*.apk | head -n 1)"
[[ -f "$BUILT" ]] || die "no release APK was produced"

# --- Verify ------------------------------------------------------------------
step "Verifying the APK"
SIGNATURE="$("$APKSIGNER" verify --verbose "$BUILT")" || die "the APK signature does not verify"
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" <<<"$SIGNATURE" || die "the APK is not signed with scheme v2"
BADGING="$("$AAPT2" dump badging "$BUILT")"
grep -q "versionName='$VERSION'" <<<"$BADGING" || die "the APK version is not $VERSION"
if grep -q "application-debuggable" <<<"$BADGING"; then die "the APK is debuggable; the native engine needs a non-debuggable build"; fi
CONTENTS="$(unzip -Z1 "$BUILT")"
grep -qx "lib/arm64-v8a/liborbit-jni-spotify.so" <<<"$CONTENTS" || die "the native engine is missing from the APK"
grep -qx "assets/spotify-runtime/engine.jar" <<<"$CONTENTS" || die "the engine DEX files are missing from the APK"

mkdir -p dist
APK="dist/Spotifeur-$TAG.apk"
cp "$BUILT" "$APK"
SHA="$(shasum -a 256 "$APK" | cut -d ' ' -f 1)"
echo "$SHA  $(basename "$APK")" > "$APK.sha256"
CERTS="$("$APKSIGNER" verify --print-certs "$BUILT")"
CERT="$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$CERTS" | head -n 1)"
echo "APK: $APK"
echo "SHA-256: $SHA"
echo "Signing certificate SHA-256: $CERT"

# --- Release notes -------------------------------------------------------------
step "Writing the release notes"
NOTES_FILE="dist/release-notes-$TAG.md"
if [[ -n "$NOTES" ]]; then
  [[ -f "$NOTES" ]] || die "notes file not found: $NOTES"
  cp "$NOTES" "$NOTES_FILE"
else
  PREVIOUS="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  if [[ -n "$PREVIOUS" ]]; then RANGE="$PREVIOUS..HEAD"; else RANGE="HEAD"; fi
  {
    echo "# Spotifeur $VERSION"
    echo
    echo "## Changes"
    echo
    git log --no-merges --format='- %s' $RANGE
    echo
    echo "## Download"
    echo
    echo "- \`$(basename "$APK")\`: Android 8.0 or later, ARM64 only."
    echo
    echo "## SHA-256"
    echo
    echo '```txt'
    echo "apk:         $SHA"
    echo "certificate: $CERT"
    echo '```'
    echo
    echo "## Installation notes"
    echo
    echo "- A build signed with a different key (for example one you built yourself) must be uninstalled first. Uninstalling erases the session and downloads."
    echo "- Sign in with the email address of your Spotify account and the code Spotify sends you."
    echo "- If you prefer not to trust a prebuilt APK, build Spotifeur yourself: see the README."
  } > "$NOTES_FILE"
fi
echo "Notes: $NOTES_FILE"

if [[ "$DRY_RUN" == 1 ]]; then
  step "Dry run: nothing was tagged, pushed or published"
  exit 0
fi

# --- Publish -----------------------------------------------------------------
step "Publishing"
echo "This will create tag $TAG on $(git rev-parse --short HEAD), push it to $REPO ($VISIBILITY) and create the GitHub release with:"
echo "  $APK"
echo "  $APK.sha256"
if [[ "$YES" != 1 ]]; then
  read -r -p "Continue? [y/N] " answer
  [[ "$answer" == [yY]* ]] || die "cancelled"
fi

git tag -a "$TAG" -m "Spotifeur $VERSION"
git push origin "$TAG"

RELEASE_ARGS=(--title "Spotifeur $VERSION" --notes-file "$NOTES_FILE" --verify-tag)
[[ "$DRAFT" == 1 ]] && RELEASE_ARGS+=(--draft)
[[ "$PRERELEASE" == 1 ]] && RELEASE_ARGS+=(--prerelease)
gh release create "$TAG" "$APK" "$APK.sha256" "${RELEASE_ARGS[@]}"

step "Done"
gh release view "$TAG" --json url --jq .url

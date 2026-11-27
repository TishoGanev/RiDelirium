#!/bin/sh
# Raises the build number shared by the iPhone app (with watch and widget), the Android app and the API.
#   ./bump_build_number.sh        add one
#   ./bump_build_number.sh 130    set it (never lower than the current number)
# The number lives in BUILD_NUMBER at the repo root; commit it with the release. iOS stamps it into
# CFBundleVersion at build time, Android uses it as versionCode, and RiDelirium-Server/deploy.sh publishes it
# at GET /v1/version.
# It goes up at least once on every day something changes: .githooks/pre-commit does it on the first commit of
# the day (git config core.hooksPath .githooks).
set -eu
cd "$(dirname "$0")"

current=$(tr -dc '0-9' < BUILD_NUMBER)
next=$((current + 1))
if [ $# -gt 0 ]; then
    case "$1" in ''|*[!0-9]*) echo "Not a number: $1" >&2; exit 1;; esac
    [ "$1" -gt "$current" ] || { echo "$1 is not above the current build number $current" >&2; exit 1; }
    next=$1
fi
echo "$next" > BUILD_NUMBER
echo "Build number $current -> $next"

#!/bin/bash
# Run-script phase for the app, widget and watch targets.
#
# Stamps the shared build number into the built product's Info.plist as CFBundleVersion, so the app, its
# widget and its watch app always match each other and the Android app and the API. The number lives in
# BUILD_NUMBER at the repo root; raise it with ./bump_build_number.sh there before an upload.
set -euo pipefail

counter="${SRCROOT}/../../BUILD_NUMBER"
if [ ! -f "$counter" ]; then
    echo "error: $counter is missing" >&2
    exit 1
fi

number=$(tr -dc '0-9' < "$counter")
if [ -z "$number" ]; then
    echo "error: $counter holds no number" >&2
    exit 1
fi

# Stamp the built product's Info.plist; this runs before code signing.
plist="${TARGET_BUILD_DIR}/${INFOPLIST_PATH}"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $number" "$plist"
echo "note: ${TARGET_NAME} build number $number"

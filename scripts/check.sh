#!/bin/bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
(cd Android && ./gradlew assembleDebug testDebugUnitTest)
swift test --package-path IOS --scratch-path /tmp/intonation-core-build -c release
xcodebuild -project 'IOS/Intonation Trainer.xcodeproj' -scheme 'Intonation Trainer' \
  -sdk iphonesimulator -configuration Debug -derivedDataPath /tmp/intonation-mvp-ios \
  CODE_SIGNING_ALLOWED=NO build

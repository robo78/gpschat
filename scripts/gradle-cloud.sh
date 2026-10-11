#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=/workspace/tooling/android-sdk
export ANDROID_USER_HOME=/workspace/tooling/android-user
export GRADLE_USER_HOME=/workspace/tooling/gradle-user
export JAVA_HOME='/workspace/tooling/jdk-17.0.20.1+1'
exec ./gradlew "$@"

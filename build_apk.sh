#!/bin/bash
set -e

# Export local tool paths
export JAVA_HOME="/home/aaronlm/Android/jdk17"
export ANDROID_HOME="/home/aaronlm/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/home/aaronlm/Android/gradle-8.6/bin:$PATH"

echo "=== Cleaning and building debug APK ==="
gradle clean assembleDebug

echo "=== Build completed! ==="
find app/build/outputs/apk/ -name "*.apk"

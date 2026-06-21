#!/bin/bash
set -e

ANDROID_DIR="/home/aaronlm/Android"
mkdir -p "$ANDROID_DIR"

echo "=== Downloading and setting up portable JDK 17 ==="
if [ ! -d "$ANDROID_DIR/jdk17" ]; then
    mkdir -p "$ANDROID_DIR/jdk17"
    curl -L "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$ANDROID_DIR/jdk17.tar.gz"
    tar -xzf "$ANDROID_DIR/jdk17.tar.gz" -C "$ANDROID_DIR/jdk17" --strip-components=1
    rm "$ANDROID_DIR/jdk17.tar.gz"
    echo "JDK 17 setup complete."
else
    echo "JDK 17 already exists."
fi

export JAVA_HOME="$ANDROID_DIR/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== Downloading and setting up Android SDK cmdline-tools ==="
SDK_DIR="$ANDROID_DIR/Sdk"
mkdir -p "$SDK_DIR"

if [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
    mkdir -p "$SDK_DIR/cmdline-tools"
    curl -L "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip" -o "$SDK_DIR/cmdline-tools.zip"
    unzip -q "$SDK_DIR/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm "$SDK_DIR/cmdline-tools.zip"
    echo "cmdline-tools setup complete."
else
    echo "cmdline-tools already exists."
fi

export ANDROID_HOME="$SDK_DIR"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "=== Installing Android SDK packages ==="
# Accept licenses (disable output clutter if possible, or just pipe yes)
yes | sdkmanager --licenses > /dev/null

# Install platforms, build-tools, platform-tools
sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"

echo "=== Setup Verification ==="
java -version
sdkmanager --list_installed

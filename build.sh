#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
deps_dir="$build_dir/deps"
compiled_dir="$build_dir/compiled-res"
classes_dir="$build_dir/classes"
stage_dir="$build_dir/stage"
generated_dir="$build_dir/generated"
outputs_dir="$project_dir/dist"

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk_dir="$ANDROID_SDK_ROOT"
elif [ -n "${ANDROID_HOME:-}" ]; then
    sdk_dir="$ANDROID_HOME"
elif [ -d "$project_dir/../android-sdk" ]; then
    sdk_dir="$project_dir/../android-sdk"
else
    echo "Android SDK not found. Set ANDROID_SDK_ROOT." >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    jdk_dir="$JAVA_HOME"
elif [ -x "$project_dir/../jdk/Contents/Home/bin/javac" ]; then
    jdk_dir="$project_dir/../jdk/Contents/Home"
else
    echo "JDK not found. Set JAVA_HOME to a JDK 17+ installation." >&2
    exit 1
fi

export JAVA_HOME="$jdk_dir"
export PATH="$jdk_dir/bin:$PATH"

mkdir -p "$deps_dir/uvc" "$deps_dir/xlog" "$compiled_dir" "$classes_dir" \
    "$stage_dir/lib/arm64-v8a" "$generated_dir" "$outputs_dir"

unzip -oq "$project_dir/libs/libuvc-3.2.9.aar" -d "$deps_dir/uvc"
unzip -oq "$project_dir/libs/xlog-1.11.0.aar" -d "$deps_dir/xlog"
cp "$deps_dir/uvc/classes.jar" "$deps_dir/uvc/classes-without-monitor.jar"
zip -dq "$deps_dir/uvc/classes-without-monitor.jar" 'com/jiangdg/usb/USBMonitor*.class'

"$sdk_dir/build-tools/35.0.0/aapt2" compile \
    --dir "$project_dir/res" \
    -o "$compiled_dir/resources.zip"

"$sdk_dir/build-tools/35.0.0/aapt2" link \
    -I "$sdk_dir/platforms/android-35/android.jar" \
    --manifest "$project_dir/AndroidManifest.xml" \
    --min-sdk-version 26 \
    --target-sdk-version 32 \
    --java "$generated_dir" \
    -o "$build_dir/base.apk" \
    "$compiled_dir/resources.zip"

"$jdk_dir/bin/javac" \
    -source 8 -target 8 \
    -bootclasspath "$sdk_dir/platforms/android-35/android.jar:$sdk_dir/build-tools/35.0.0/core-lambda-stubs.jar" \
    -classpath "$deps_dir/uvc/classes.jar:$deps_dir/xlog/classes.jar" \
    -d "$classes_dir" \
    "$project_dir/src/com/codex/uvc90/MainActivity.java" \
    "$project_dir/src/com/jiangdg/utils/BuildCheck.java" \
    "$project_dir/src/com/jiangdg/utils/HandlerThreadHandler.java" \
    "$project_dir/src/com/jiangdg/usb/USBMonitor.java" \
    "$generated_dir/com/codex/uvc90/R.java"

"$jdk_dir/bin/jar" cf "$build_dir/app-classes.jar" -C "$classes_dir" .
mkdir -p "$build_dir/dex"

"$sdk_dir/build-tools/35.0.0/d8" \
    --lib "$sdk_dir/platforms/android-35/android.jar" \
    --min-api 26 \
    --output "$build_dir/dex" \
    "$build_dir/app-classes.jar" "$deps_dir/uvc/classes-without-monitor.jar" "$deps_dir/xlog/classes.jar"

cp "$build_dir/dex/classes.dex" "$stage_dir/classes.dex"
cp "$deps_dir/uvc/jni/arm64-v8a/"*.so "$stage_dir/lib/arm64-v8a/"
(cd "$stage_dir" && zip -qr "$build_dir/base.apk" classes.dex lib)

"$sdk_dir/build-tools/35.0.0/zipalign" -f 4 \
    "$build_dir/base.apk" "$build_dir/UVC90-unsigned.apk"

if [ ! -f "$build_dir/debug.keystore" ]; then
    "$jdk_dir/bin/keytool" -genkeypair -v \
        -keystore "$build_dir/debug.keystore" \
        -storepass android -alias androiddebugkey -keypass android \
        -dname "CN=UVC90 Debug,O=Codex,C=CN" \
        -keyalg RSA -keysize 2048 -validity 10000
fi

"$sdk_dir/build-tools/35.0.0/apksigner" sign \
    --ks "$build_dir/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$outputs_dir/UVC90-Camera-v4.2.apk" \
    "$build_dir/UVC90-unsigned.apk"

"$sdk_dir/build-tools/35.0.0/apksigner" verify --verbose \
    "$outputs_dir/UVC90-Camera-v4.2.apk"

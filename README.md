# UVC90 Camera for Android

**English** | [简体中文](README_CN.md)

An experimental video-recording app for Samsung Android phones and a specific UVC global-shutter camera. The current version is **v4.2**, targeting USB VID `1bcf` and PID `28c4`.

## Features

- Requests a fixed MJPEG stream at `1920×1080 @ 90 FPS` without changing resolution or cropping during capture.
- Records H.264 MP4 video at a selectable 90, 60, or 30 FPS with a target bitrate of 60 Mbps.
- Displays the full 1080p frame while sampling the preview at 15 FPS by default to reduce phone load without changing the capture stream.
- Shows the incoming camera frame rate and the recording write rate.
- Provides controls for exposure, gamma, brightness, saturation, contrast, hue, sharpness, automatic white balance, backlight compensation, and anti-flicker frequency.
- Reads hardware controls back immediately after a change so the UI does not claim that an unsupported value was accepted.
- Detects standard UVC Gain/ISO dynamically and only shows the control when the camera actually exposes it.
- Includes fixes for Samsung Android USB permission callbacks, 0 FPS at startup, black preview after parameter changes, and swapped red/blue preview channels.
- Allows the parameter panel to be collapsed for an unobstructed preview.

## v4.2 Default Image Strategy

- Normal lighting: 9.5 ms exposure, gamma 130, and brightness 0.
- Automatic white balance enabled, saturation 64, contrast 0, hue 0, sharpness 2, backlight compensation enabled, and 50 Hz anti-flicker.
- In low light, exposure is raised first, up to 10.5 ms, remaining below the approximately 11.1 ms frame period required for 90 FPS.
- If the image remains underexposed, gamma is temporarily raised up to 170, followed by brightness up to +12.
- When lighting recovers, brightness, gamma, and exposure return to their defaults in reverse order.
- Software brightness and gamma are not labeled as ISO. If hardware Gain is unavailable, the app reports it as unsupported.

## Build

Requirements:

- macOS or Linux
- JDK 17 or newer
- Android SDK Platform 35
- Android Build Tools 35.0.0

Set the environment variables and run:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./build.sh
```

The signed debug APK is generated at `dist/UVC90-Camera-v4.2.apk`. The build script uses the pinned UVC and logging dependencies in `libs/` to reproduce the hardware-tested build.

## Usage

1. Install the APK on a Samsung phone and grant the Camera permission.
2. Connect the target UVC camera through a data-capable OTG adapter.
3. Tap **Request permission and connect 1080p90**, then select **Allow** in the Android USB dialog.
4. Confirm that the app displays `MJPEG 1920×1080 @ 90 FPS`, then start recording.
5. Recordings are saved in the system `Movies/UVC90` directory.

This is a validation app for a specific hardware combination. UVC control ranges may vary across camera firmware versions; treat the hardware readback displayed by the app as authoritative.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party dependencies and details of this project's `USBMonitor` modifications.

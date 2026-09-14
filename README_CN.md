# UVC90 Camera for Android

[English](README.md) | **简体中文**

面向三星 Android 手机和指定 UVC 全局快门相机的实验性录像应用。当前版本为 **v4.2**，目标设备为 USB VID `1bcf` / PID `28c4`。

## 下载安装

**[直接下载 UVC90 Camera v4.2 APK](https://github.com/wuwoasd811-art/uvc90-camera-android/releases/download/v4.2/UVC90-Camera-v4.2.apk)**

可以前往 [全部版本页面](https://github.com/wuwoasd811-art/uvc90-camera-android/releases) 查看安装包和版本说明。源代码、使用说明和构建方法均保存在当前仓库中。

## 主要能力

- 固定请求 MJPEG `1920×1080 @ 90 FPS`，不中途切换分辨率或裁剪。
- H.264 MP4 录像，录像帧率可选 90/60/30 FPS，目标码率 60 Mbps。
- 完整 1080p 预览，默认仅抽取 15 FPS 显示，降低手机负载但不改变采集源。
- 显示相机实收帧率和录像写入帧率。
- 可调曝光、Gamma、亮度、饱和度、对比度、色调、锐度、自动白平衡、逆光补偿和防频闪。
- 修改硬件参数后立即读回，避免界面数值变化但相机未实际接受。
- 动态检测标准 UVC Gain/ISO；只有相机确实开放该控制时才显示滑条。
- 修复三星 Android USB 授权回调、启动 0 FPS、参数调整后黑屏及预览红蓝通道异常。
- 参数面板可收起，以完整显示预览画面。

## v4.2 默认画质策略

- 正常光线：曝光 9.5 ms、Gamma 130、亮度 0。
- 自动白平衡开启、饱和度 64、对比度 0、色调 0、锐度 2、逆光补偿开启、50 Hz 防频闪。
- 弱光时先把曝光提高到最多 10.5 ms，以保证曝光时间不超过 90 FPS 约 11.1 ms 的帧周期。
- 曝光不足时临时把 Gamma 提高到最多 170，再把亮度提高到最多 +12。
- 环境恢复后按亮度、Gamma、曝光的顺序回到默认值。
- 不把软件亮度或 Gamma 标记为 ISO；没有硬件 Gain 时明确显示“不支持”。

## 构建

要求：

- macOS 或 Linux
- JDK 17+
- Android SDK Platform 35
- Android Build Tools 35.0.0

设置环境变量后执行：

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
./build.sh
```

存在私有的官方签名密钥时，APK 会生成到 `dist/UVC90-Camera-v4.2.apk`。从公开仓库全新下载、但没有官方密钥时，会生成使用开发签名的 `dist/UVC90-Camera-v4.2-dev.apk`；开发版不能覆盖安装 GitHub 上的官方版本。签名说明见 [signing/README.md](signing/README.md)。

构建脚本使用仓库 `libs/` 中固定版本的 UVC 和日志依赖，以复现当前经过硬件验证的源码版本。

## 后续升级到 v4.3 或更高版本

Android 只有在新旧 APK 的包名和签名证书都相同时，才允许直接覆盖升级。因此，任何官方 v4.3 或更高版本都必须继续使用包名 `com.codex.uvc90`，并沿用 v4.2 的同一份私有签名密钥。

以后换 Mac 或其他电脑构建时，需要把安全备份的密钥恢复到 `signing/uvc90-release.keystore`，也可以通过 `UVC90_KEYSTORE` 指向密钥的安全存放位置。发布前必须核对证书 SHA-256 指纹与 [signing/README.md](signing/README.md) 中记录的值一致。

如果 v4.2 的密钥丢失并改用新密钥，Android 会认为签名不一致，拒绝把新版作为更新安装。用户只能先卸载 v4.2，再安装新版；卸载可能清除应用设置及应用私有数据。公共 `Movies/UVC90` 文件夹中的录像通常会保留，但卸载前仍应单独备份。

## 运行说明

1. 在三星手机上安装 APK 并授予“相机”权限。
2. 通过支持数据传输的 OTG 转接器连接目标 UVC 相机。
3. 点击“请求权限并连接 1080p90”，在系统 USB 弹窗中选择允许。
4. 确认界面显示 `MJPEG 1920×1080 @ 90 FPS` 后开始录像。
5. 视频保存至系统 `Movies/UVC90` 目录。

这是面向特定硬件组合的验证应用。不同固件可能提供不同的 UVC 控制范围，界面显示的相机实读结果应作为最终依据。

第三方依赖及本项目对 `USBMonitor` 的修改说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

项目源码采用 [Apache License 2.0](LICENSE)。仓库内的第三方组件继续遵循 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中列出的各自许可证。

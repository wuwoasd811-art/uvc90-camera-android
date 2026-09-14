# Third-party notices

This repository includes binary and source components from the following projects.

## AndroidUSBCamera / libuvc

- Upstream: <https://github.com/jiangdongguo/AndroidUSBCamera>
- Revision used during development: `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`
- License: Apache License 2.0; see `third_party/AndroidUSBCamera-LICENSE.txt`.

The included `USBMonitor.java` contains local compatibility changes for Samsung/Android USB permission delivery:

- use a mutable permission `PendingIntent` where Android must attach USB grant extras;
- allow an already-authorized device to be connected through the monitor;
- remove an unnecessary AndroidX annotation dependency.

## XLog

- Upstream: <https://github.com/elvishew/xLog>
- Included binary: `libs/xlog-1.11.0.aar`
- License: Apache License 2.0; see `third_party/xLog-LICENSE.txt`.

No ownership of these third-party components is claimed. Their respective licenses continue to apply.

# APK signing

The private signing key is intentionally excluded from Git. Never commit or share it.

Using the same certificate as v4.2 is only required when a later APK should install directly as an update. It is not required in order to publish v4.3 or a later version. The v4.2 certificate SHA-256 fingerprint is:

```text
cd0a5e20bfe88deca2959241dbe4ce0515ef5f336e0eeeb95037b89353d80b2e
```

By default, `build.sh` looks for the official key at `signing/uvc90-release.keystore`. A different secure location can be selected with `UVC90_KEYSTORE`; credentials can be supplied through `UVC90_STORE_PASSWORD`, `UVC90_KEY_PASSWORD`, and `UVC90_KEY_ALIAS`.

If no official key is available, the script creates a separate development key under `build/` and outputs an APK ending in `-dev.apk`. That APK is suitable for testing but cannot update the official release.

Keeping an encrypted backup of the v4.2 key preserves the option of direct in-place updates. If the project intentionally uses a different key later, the new release remains installable after users uninstall v4.2 first.

## Building on another computer

There are two valid release paths:

- Restore the v4.2 key and verify the fingerprint above when direct in-place updates are desired.
- Use a new signing key and clearly tell users to back up their data, uninstall v4.2, and then install v4.3 as a fresh app.

Do not describe a differently signed APK as a direct update to v4.2. Android will reject that installation until the existing app is uninstalled.

# APK signing

The private signing key is intentionally excluded from Git. Never commit or share it.

Official releases must use the same certificate as v4.2 so Android can install them as updates. The expected certificate SHA-256 fingerprint is:

```text
cd0a5e20bfe88deca2959241dbe4ce0515ef5f336e0eeeb95037b89353d80b2e
```

By default, `build.sh` looks for the official key at `signing/uvc90-release.keystore`. A different secure location can be selected with `UVC90_KEYSTORE`; credentials can be supplied through `UVC90_STORE_PASSWORD`, `UVC90_KEY_PASSWORD`, and `UVC90_KEY_ALIAS`.

If no official key is available, the script creates a separate development key under `build/` and outputs an APK ending in `-dev.apk`. That APK is suitable for testing but cannot update the official release.

Keep at least one encrypted offline backup of the official key. Losing it permanently prevents future APKs from updating existing installations.

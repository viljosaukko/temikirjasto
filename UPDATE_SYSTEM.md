# Update System

This app now supports a GitHub Releases based updater.

## How it works

- The app checks this manifest URL on startup:
  - `https://github.com/viljosaukko/temikirjasto/releases/latest/download/update.json`
- The manifest tells the app the latest `versionCode`, `versionName`, APK URL, and optional SHA-256 checksum.
- If the manifest version is newer than the installed app, the app shows an update dialog.
- The APK is downloaded to the app cache, verified if a SHA-256 is provided, and then launched in the Android package installer.

## Release format

Attach these files to each GitHub Release:

- `update.json`
- `kirjastobotti.apk`

Example `update.json`:

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "apkUrl": "https://github.com/viljosaukko/temikirjasto/releases/latest/download/kirjastobotti.apk",
  "sha256": "PUT_THE_APK_SHA256_HERE",
  "notes": "Fixes startup crash and improves navigation stability."
}
```

## Commercial deployment notes

- Sign the APK with a stable release keystore and keep that keystore private.
- Bump `versionCode` for every release.
- Keep the release asset names stable so the updater URL never changes.
- If you want stronger integrity guarantees, always publish the SHA-256 checksum in the manifest.
- If you need a private update channel, move the manifest to your own HTTPS endpoint and point the app to that URL.

## Updating the manifest

When shipping a new version:

1. Build the signed release APK.
2. Compute its SHA-256 checksum.
3. Update `update.json` with the new version information.
4. Upload both files to the GitHub Release.
5. Confirm the release assets are reachable from the `latest/download` URL.

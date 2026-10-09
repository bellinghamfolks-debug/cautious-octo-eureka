# My Coffee Lounge Arabic / TalkBack experimental APK

This is a third-party, **unofficial experimental build**, not supplied or endorsed by De'Longhi. The app has not been installation-tested on a physical Android 16 device. Arabic UI strings do not guarantee full TalkBack support.

## Current APK: official Android SDK signature verification passed

Path: `releases/DeLonghi_MyCoffeeLounge_Arabic_ARM64_APK_Repaired_Experimental.apk`

SHA-256: `ae4ea11b10e2c0547ddc27d84666c0746488bf1ae053f34539f4bc722915052d`

The original APK in this branch had a **malformed v2 signature block**, independently confirmed by the official Android SDK `apksigner` tool:

- `APK Signature Scheme v2 signer #1: Malformed ... signature record #1`
- `APK Signature Scheme v2 signer #2: Malformed signer block`

The branch workflow `.github/workflows/verify-resign-delonghi.yml` reassembled the ZIP entries without old signatures, used Android SDK `zipalign`, and signed with Android SDK `apksigner` using a newly generated testing key. It passed `apksigner verify` with SDK 32 and SDK 36 compatibility tests using v3 signatures, and `aapt dump badging` parsed the manifest. A payload comparison ensured the non-signature entries remained unchanged.

The complete verification output is in `releases/DeLonghi_Android16_Signing_Verification.txt`.

**Important:** The test signing key is not De'Longhi's key. It is a new ephemeral testing key, so it **cannot update** the official app or a previous test build in place. It is not guaranteed that account login or integrations with official services will work. Future test versions may have different signing identities as well.

## Download using git

```bash
git clone --branch delonghi-arabic-talkback-apk --single-branch https://github.com/bellinghamfolks-debug/cautious-octo-eureka.git
```

You will find the APK under `releases/`. Avoid installing until you have backed up your official app's access and confirmed you can safely restore it.

Older binary revisions remain in Git history. This branch has not modified `main`.

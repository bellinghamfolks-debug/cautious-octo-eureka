# 3.8.5 test delivery

Source commit: 498138be8f60df6204a1abf1b36421c5c432b4d1.
CI run: 35927754937. Build/lint/unit checks, 18 managed Android tests and candidate packaging all succeeded.
Package: com.abdullah.visionbridge.stable; versionCode 46.
Final APK: VisionBridge-3.8.5-stable-signed-arm64.apk.
Final APK SHA-256: db04fda461f3dc87fac22acf8a16e8218ee16ef88500827616ca17c077152c6c.
Signing certificate SHA-256: 349726219c7a59aee813745ff6d00599cf396f03cf6e2b3f48dcd29f023df9bd.

The final APK was signed privately after CI and its pinned signature verified with apksigner.
The private recovery archive has been saved persistently for the owner; restore the same key,
never generate a replacement. See STABLE_SIGNING.md. No private diagnostic frames, keys or
passwords were uploaded to git or Actions. The prior validation app's ephemeral signing key
was not retained, so this first build installs separately. Subsequent builds must preserve this
package/certificate and increase versionCode.

This is a test delivery, not complete performance or optical acceptance. The diagnostic bundle
contains no successful cloud submission. Credential-readiness guards and explicit safe failure
locations fix identified code defects; the old generic exceptions do not prove every root cause.
Cloud accuracy, useful end-to-end latency and the remaining tracking tail latency require further
phone verification. Local full Gradle validation was blocked by DNS; CI used a complete toolchain.

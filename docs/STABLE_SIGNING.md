# Stable APK signing

Permanent application ID: `com.abdullah.visionbridge.stable`.
Certificate SHA-256: `349726219c7a59aee813745ff6d00599cf396f03cf6e2b3f48dcd29f023df9bd`.

The earlier validation builds used ephemeral CI debug keys. Their private signing keys were
not retained. Do not claim an in-place update from those APKs. The first stable-signature build
installs separately, preserving existing applications. Every subsequent stable build must use
this same application ID and certificate and an increasing versionCode.

The private recovery archive is named `VisionBridge-private-signing-recovery-v1.zip` and is
saved privately for the owner. NEVER commit/upload the key or password to public git, CI logs,
Actions artifacts, or releases. Restore it privately before signing. Do not regenerate it.
CI produces a tested candidate only; it is not the distributable final signature.
Run `scripts/sign_candidate.py` with an explicit private keystore and password file. The helper
verifies the certificate before signing, checks the APK signature afterwards, and refuses to
replace an existing output. Distribute only that verified output. The public certificate digest
is deliberately pinned here; changing it requires an explicit migration decision.

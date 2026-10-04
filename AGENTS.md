# MuNote Repository Rules

These rules are mandatory for anyone or any agent that modifies, builds, or publishes MuNote.

## Read before changing release/signing logic

Before editing any release, signing, versioning, package-id, or CI configuration, read:

- `README.md`
- `docs/SIGNING.md`
- `.github/workflows/release.yml`
- `app/build.gradle.kts`

## Permanent Android production identity

MuNote production prereleases and stable releases MUST keep the same Android application identity and signing certificate.

Production package:

`dev.munote.app`

Permanent production certificate SHA-256:

`34:24:ED:F8:19:B7:48:74:61:14:DF:15:B2:B8:33:3D:C0:88:F9:28:51:6B:CC:68:E8:36:FC:AB:02:1B:85:92`

This certificate fingerprint is public metadata. The private keystore and passwords MUST NOT be committed to the repository.

### Hard signing rules

1. Never generate or substitute a new production signing key unless the repository owner explicitly approves a signing migration.
2. Never use `app/keystore/munote-test.keystore` for a production prerelease or stable release.
3. Never change the production package id `dev.munote.app` without an explicit migration plan approved by the owner.
4. A production build MUST fail if the configured private key does not match the pinned SHA-256 fingerprint above.
5. The final APK signer MUST be verified after build and before upload.
6. Missing signing secrets are a hard failure. Do not fall back to debug/test signing.
7. Any intentional signing migration must be documented before release, including upgrade/install consequences. A signer change normally breaks in-place Android upgrades.
8. Historical Test 112 and other debug/test builds use a separate public test identity and are not the production signing baseline.

Required GitHub Actions secrets for production release builds:

- `MUNOTE_SIGNING_KEY_BASE64`
- `MUNOTE_SIGNING_STORE_PASSWORD`
- `MUNOTE_SIGNING_KEY_ALIAS`
- `MUNOTE_SIGNING_KEY_PASSWORD`

The owner keeps the permanent private signing material offline. Do not print, upload, or expose it in logs, issues, PRs, Releases, or source control.

## Release policy

- User-downloadable candidate builds go to GitHub **Pre-release**, not Actions Artifact.
- Stable releases are produced by promoting the already-tested Pre-release in place.
- Promotion MUST NOT rebuild or replace the APK.
- Do not overwrite an existing published version with different bytes.
- If a candidate fails testing, fix the code and use a new version.
- Existing Pre-releases and Releases are retained unless the owner explicitly asks to delete one.
- Ordinary CI may compile/test locally on the runner but does not need to retain an APK Artifact.
- Gradle dependency cache is allowed and is not the same thing as downloadable build Artifact storage.

## Handoff rule

Any new maintainer or agent must treat `docs/SIGNING.md` and the pinned production certificate above as project invariants. If signing credentials are unavailable, stop the release path and report the missing dependency; do not create a replacement signer.

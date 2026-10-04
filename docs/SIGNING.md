# MuNote Android Signing

## Permanent production signer

Future MuNote prereleases and stable releases use one permanent private signing identity.

Certificate SHA-256:

`34:24:ED:F8:19:B7:48:74:61:14:DF:15:B2:B8:33:3D:C0:88:F9:28:51:6B:CC:68:E8:36:FC:AB:02:1B:85:92`

This fingerprint is public metadata. The keystore bytes and passwords must never be committed.

The release workflow requires these GitHub Actions secrets:

- `MUNOTE_SIGNING_KEY_BASE64`
- `MUNOTE_SIGNING_STORE_PASSWORD`
- `MUNOTE_SIGNING_KEY_ALIAS`
- `MUNOTE_SIGNING_KEY_PASSWORD`

CI verifies the restored keystore certificate against the pinned fingerprint above and verifies the produced APK before uploading it to a prerelease.

## Historical test signer

The repository's public `app/keystore/munote-test.keystore` is only for historical/debug test builds such as Test 112. It is not the production signing identity and must not be used for releases.

## Release rule

A candidate build is compiled and signed once, uploaded as a GitHub Pre-release, then promoted in place to a stable Release after acceptance. Promotion does not rebuild or replace the APK.

# Release runbook

Only cut or alter a release when the user explicitly requests it. The current
automation in `.github/workflows/release.yml` is authoritative.

## Preflight

1. Inspect the branch, status, staged diff, and recent tags. Do not include
   unrelated work or assume uncommitted user changes should be released.
2. Run the local FOSS release-parity unit/integration, lint, and build checks
   from [`testing.md`](testing.md). Do not run local emulator E2E.
3. Push the candidate commit and require the complete `CI` workflow—including
   its `headless-core` E2E job and final `gate` job—to pass before tagging.
4. Confirm the requested semantic version and that the `v<semver>` tag does not
   already exist locally or remotely.
5. Confirm the four signing secrets exist in GitHub: `KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

The Google/FCM distribution is paused. Do not build, sign, attach, or publish a
Google APK, and do not require Firebase client or relay configuration for a
release, until the maintainer explicitly reactivates it.

## Cut the release

```sh
git tag -s v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

Replace the example version with the approved tag. The workflow uses the tag as
`versionName`, the GitHub run number as `versionCode`, and the tagged commit SHA
as embedded source provenance, then builds and signs the FOSS APK and publishes:

- the renamed FOSS APK;
- complete corresponding libbox source;
- GPL and IBM Plex license files;
- release-specific third-party notices; and
- `SHA256SUMS`.

The focused managed-device smoke remains available separately, and exhaustive
E2E runs manually/nightly. Neither currently gates a release because the hosted
managed emulator can fail in System UI before MOTD starts. The release job still
runs the FOSS release build, tests, and lint.

The release description should contain a changelog of commits since
the last version.

## Failure recovery

- Inspect the failed job before changing code or secrets; distinguish runner,
  signing, Gradle, and packaging failures.
- A retry runs against the same tagged commit. A source fix on `main` is not in
  that tag.
- Do not force-move a tag or delete a published release without explicit
  maintainer direction. Prefer a new patch release after fixing and verifying
  the cause.
- If a tag has never produced a published release, recreating it is still a
  history rewrite and requires explicit approval.
- Respect the user’s monitoring instruction: watch CI only when requested, and
  otherwise return the release/tag reference for them to follow.

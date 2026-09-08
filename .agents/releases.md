# Release runbook

Only cut or alter a release when the user explicitly requests it. The current
automation in `.github/workflows/release.yml` is authoritative.

## Preflight

1. Inspect the branch, status, staged diff, and recent tags. Do not include
   unrelated work or assume uncommitted user changes should be released.
2. Run only the nearest local checks from [`testing.md`](testing.md). Do not
   duplicate hosted release parity or run local emulator E2E.
3. Push the candidate commit and require the complete `Required CI` workflow—including
   its `headless` E2E job and final `gate` job—to pass before tagging. Confirm the
   Android job's **Build and verify signed release APK** step succeeded; a green
   test/lint-only run or skipped packaging step is not release-build evidence.
4. Confirm the requested semantic version and that the `v<semver>` tag does not
   already exist locally or remotely.
5. Confirm the four signing secrets exist in GitHub: `KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
6. Write `fastlane/metadata/android/en-US/changelogs/<motdVersionCode>.txt` for
   the new version code. The release workflow uses this as the GitHub release's
   detailed "What's new" section, and F-Droid reads the same file straight from
   this repository. A missing or empty file fails the release job. Keep it
   user-facing and under 500 characters.

## Cut the release

```sh
git tag -s v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

Replace the example version with the approved tag. The workflow requires the
tag to match `v<versionName>` from `gradle.properties`, uses the matching
`motdVersionCode`, and embeds the tagged commit SHA as source provenance. It
then builds and signs the FOSS APK and publishes:

- the renamed FOSS APK;
- complete corresponding libbox source;
- GPL and Roboto license files;
- release-specific third-party notices; and
- `SHA256SUMS`.

Required CI keeps four connected real-stack journeys in its stable gate;
exhaustive host-driven E2E is manual-only. The release workflow verifies that
the exact tagged SHA's latest `Required CI / gate` check succeeded, then builds,
signs, verifies, and publishes the FOSS release without rerunning that SHA's
tests, formatting, or lint.

CI and publication both use `tools/build-signed-release.sh`. CI explicitly passes
`--ci-key` to generate a disposable signing key; publication requires all four
`MOTD_KEYSTORE_PATH`, `MOTD_KEYSTORE_PASSWORD`, `MOTD_KEY_ALIAS`, and
`MOTD_KEY_PASSWORD` variables and never falls back to a disposable key. Both paths
cryptographically verify the APK signature and check the signing-block policy.
The release-only `:app:verifyReleaseAiNativeArtifacts` task checks the actual AGP
release APK without pulling debug/E2E builds into the same heap. Packaging uses a
fresh daemon, no parallel project execution, and at most two workers. The existing
debug AAR/debug APK/E2E `:app:verifyAiNativeArtifacts` check remains a separate CI
invocation and retains its F-Droid preassemble contract.

The release description starts with the same detailed, user-facing changelog
shown on F-Droid, followed by source/license details and GitHub's generated full
changelog since the previous version.

## F-Droid

The app is merged into fdroiddata. The metadata is external to this repository
and is not advanced by the release workflow. `docs/fdroid.md` holds the recipe
and `docs/human-fdroid-update.md` the per-release steps.

F-Droid's `checkupdates` bot proposes each new version on its own by copying the
previous build entry. Do not clone fdroiddata, push a branch, or open or comment
on a merge request unless the maintainer asks. A tag with green CI is not by
itself grounds to touch fdroiddata.

The one thing that does belong in this repository is the recipe drifting from
the source tree. When a change alters the Gradle flavors or tasks, the NDK, Go,
JDK, SDK, or build-tools versions, the libbox manifest filename, the ABI set, or
any path listed under `rm:`, say so in the change and flag that the next
fdroiddata entry cannot be a plain copy.

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

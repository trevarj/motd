# Android distribution

Keep the existing Google-free APK, package `io.github.trevarj.motd`, and release
signing key. Android 8.0+ (API 26) and a 64-bit ARM (`arm64-v8a`) device are
required; push uses [UnifiedPush](ntfy-push.md), not Firebase. Verified original
APKs preserve update compatibility with existing GitHub/F-Droid installations.
Do not rebuild or re-sign an APK for a catalogue or change the app identity.

## Channels and publication status

Status checked **2026-10-11**:

- **GitHub**: [official upstream releases](https://github.com/trevarj/motd/releases/latest).
- **F-Droid**: [existing package](https://f-droid.org/packages/io.github.trevarj.motd),
  sharing the upstream signing key. Keep the existing
  [release](human-releasing.md) and [F-Droid update](human-fdroid-update.md) processes.
- **Obtainium**: direct import of official GitHub releases, not a separate
  maintainer upload or optional catalogue acceptance. No catalogue account is needed.
- **OpenAPK**: [existing third-party catalogue page](https://www.openapk.net/motd/io.github.trevarj.motd/).
  Its **0.20.3 (20003)** download was verified byte-for-byte against upstream,
  including the original signing certificate. No maintainer upload or duplicate
  inclusion request was needed.
- **Appteka**: [older community listing](https://appteka.store/app/39er313876),
  version **0.14.12 (14012)** uploaded by **Silent Miqo**, updated **2026-08-27**.
  Its [public download page](https://appteka.store/apps/39er313876/download) also
  identifies 0.14.12. The [public name search](https://appteka.store/search?q=motd)
  returned this sole MOTD listing plus Termux:Launcher; the
  [package-string search](https://appteka.store/search?q=io.github.trevarj.motd)
  returned no results. No public **0.20.3 (20003)** artifact was found through
  those pages; this is not proof that an unindexed or pending upload is absent.
  The older upload is not evidence of maintainer ownership or current publication.
  The current packet is prepared, but native authentication/terms acceptance,
  device transfer/upload, moderation, and public-APK verification remain pending.
  No availability badge yet.

### Popularity and eligibility

GitHub API counts retrieved **2026-10-11 06:00:59 UTC**, ranked by stars of the
linked repositories; every entry has at least 1,000. Stars measure developer
interest, not store users, safety, or market share. Clients and official mirrors
are identified below, not counted as independent publishing destinations.
Zapstore and projects below 1,000 stars are outside the selected scope.

| Rank | Project / measured repository | Stars | Role and MOTD action |
|---|---|---:|---|
| 1 | [Obtainium](https://github.com/ImranR98/Obtainium) | 20,331 | Source updater. Direct GitHub import now; optional catalogue is age-blocked until November 8. |
| 2 | [Komi Store (formerly GitHub Store)](https://github.com/komi-store/komi-store) | 19,304 | Release discovery/client. Existing GitHub APK is the source; no publisher upload or invented listing badge. |
| 3 | [Droid-ify](https://github.com/Droid-ify/client) | 7,571 | Official GitHub mirror of the Codeberg F-Droid client. Uses the existing MOTD F-Droid listing; no independent submission. |
| 4 | [Neo Store](https://github.com/NeoApplications/Neo-Store) | 5,411 | F-Droid client; uses the existing listing, not an independent store submission. |
| 5 | [APKUpdater](https://github.com/rumboalla/apkupdater) | 4,036 | Source updater/aggregator, not a publisher destination; no new channel configured. |
| 6 | [F-Droid client](https://github.com/f-droid/fdroidclient) | 3,187 | Official GitHub mirror of GitLab upstream. MOTD is already listed; preserve the existing channel. |
| 7 | [Aurora Store](https://github.com/whyorean/AuroraStore) | 3,088 | Google Play frontend, not its own publisher store. Excluded: MOTD has no Play listing and remains Google-free. |
| 8 | [Accrescent](https://github.com/accrescent/accrescent) | 2,280 | Real store, but developer sign-up is allowlisted and new allowlist requests are closed. No submission/badge. |
| 9 | [Appteka](https://github.com/solkin/appteka-android) | 1,283 | Free APK exchange. Older community upload exists; current upstream-signed upload requires the supported authenticated Android UI. |
| 10 | [OpenAPK](https://github.com/mobilenetworkltd/openapk) | 1,100 | Existing third-party catalogue. Actual 0.20.3 download verified against upstream; official banner added, no new-app request. |

Policies were rechecked against [Obtainium criteria](https://github.com/ImranR98/apps.obtainium.imranr.dev/blob/main/APP_CRITERIA.md),
[Obtainium badging](https://github.com/ImranR98/apps.obtainium.imranr.dev/blob/main/BADGING.md),
[Accrescent onboarding](https://accrescent.app/docs/guide/getting-started/new-app/),
[Appteka publication instructions](https://appteka.store/pubinstr), and
[Appteka terms](https://appteka.store/legal) (observed revision 2024-10-27).
Recheck live policies, counts, and duplicates before a later submission. A paid,
closed, re-signing, or exclusive-distribution requirement removes a destination
from this scope; do not work around it by changing MOTD.

## Obtainium direct import

[<img src="assets/badges/obtainium.png" alt="Add motd with Obtainium" height="80">](https://trevs.site/motd/installation.html#obtainium)

The [installation page](https://trevs.site/motd/installation.html#obtainium)
opens the native import only when the user taps its badge. Without a URI handler,
install [Obtainium](https://github.com/ImranR98/Obtainium/releases/latest), or paste
`https://github.com/trevarj/motd` into its Add app screen. GitHub and F-Droid remain
ordinary fallback links. No automatic redirect, JavaScript, or tracking is used.

Per [Obtainium deep links](https://wiki.obtainium.imranr.dev/deep_links/), the
configuration is exactly these four keys, compact and URL-encoded once:

```json
{"id":"io.github.trevarj.motd","url":"https://github.com/trevarj/motd","author":"trevarj","name":"motd"}
```

```text
obtainium://app/%7B%22id%22%3A%22io.github.trevarj.motd%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Ftrevarj%2Fmotd%22%2C%22author%22%3A%22trevarj%22%2C%22name%22%3A%22motd%22%7D
```

Keep default stable-release settings; do not enable prereleases. The unchanged
local badge is [official Obtainium artwork](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png),
not a catalogue-acceptance or store-verification seal.

The optional catalogue requires an official source repository with **at least
35 stars** and **four months** of age. MOTD had 35 stars at the observation above
and was created **2026-07-08T13:33:41Z**; it is age-blocked until
**2026-11-08T13:33:41Z**. No catalogue issue/PR is submitted now. After that instant,
the maintainer may request catalogue submission; the submitting agent must first
recheck live criteria, stars, and open/closed duplicate requests. Direct import
already works independently; there is no timer or workaround submission.

## OpenAPK verified third-party catalogue

[<img src="assets/badges/openapk.png" alt="Get motd on OpenAPK" height="80">](https://www.openapk.net/motd/io.github.trevarj.motd/)

The [existing package page](https://www.openapk.net/motd/io.github.trevarj.motd/)
is maintained by a third-party catalogue, not a new maintainer publication.
On **2026-10-11**, its actual
[download-page link](https://www.openapk.net/motd/io.github.trevarj.motd/apk/download)
redirected to the
[upstream v0.20.3 APK](https://github.com/trevarj/motd/releases/download/v0.20.3/motd-v0.20.3-foss.apk).
The downloaded **114,138,530-byte** file passed a byte-for-byte comparison with
the frozen upstream APK. Its SHA-256 was
`96a7b35c012203ccd807c5e65883ea31fd0fd3f21e4bbfd835315928a6b39028`.

Project-pinned build-tools **36.0.0** verified a valid APK Signature Scheme v2
signature with one signer, **CN=MOTD Release, O=trevarj**, and certificate SHA-256
`4104a03bbc48942df8346fbd331f7761d13f68af4a2ff4d14f730e501ce728c3`,
matching the upstream/F-Droid pin. Actual manifest/badging confirmed package
`io.github.trevarj.motd`, version **0.20.3 / 20003**, minimum API **26**, target
API **36**, and **arm64-v8a only**. `debuggable` and `testOnly` were absent
(both default false). The download page's generic 32-bit/x86 claims do not
make MOTD compatible with those devices.

The unchanged local banner is
[OpenAPK's official artwork](https://www.openapk.net/images/openapk-badge.png),
not a store-verification seal. No new-app request or metadata issue was submitted.
For later updates, repeat the actual-download verification below; if the catalogue
lags, verify against its named upstream release rather than claiming it is current.

## Frozen upstream input and verification

The packet uses [v0.20.3](https://github.com/trevarj/motd/releases/tag/v0.20.3),
published 2026-10-08 and verified 2026-10-11. Do not mix another release's APK,
metadata, or changelog into this execution.

| Field | Verified value |
|---|---|
| APK | `motd-v0.20.3-foss.apk` — 114,138,530 bytes |
| Package | `io.github.trevarj.motd` |
| Version / code | `0.20.3` / `20003` |
| Android / ABI | Minimum API 26, target API 36; `arm64-v8a` only |
| Signature | Valid APK Signature Scheme v2; one original upstream signer |
| Manifest flags | `debuggable` and `testOnly` absent (both default false) |
| APK SHA-256 | `96a7b35c012203ccd807c5e65883ea31fd0fd3f21e4bbfd835315928a6b39028` |
| Certificate SHA-256 | `4104a03bbc48942df8346fbd331f7761d13f68af4a2ff4d14f730e501ce728c3` |

The certificate matches the [existing F-Droid pin](fdroid.md#reproducible-signing).
From the repository root, reproduce the frozen download and checks with the
project-pinned Android SDK (build-tools 36.0.0), not a global SDK:

```sh
PACKET=$(mktemp -d /tmp/motd-android-check.XXXXXX)
gh release download v0.20.3 --repo trevarj/motd \
  --pattern motd-v0.20.3-foss.apk --pattern SHA256SUMS --dir "$PACKET"
APK="$PACKET/motd-v0.20.3-foss.apk"
(cd "$PACKET" && sha256sum --check --ignore-missing SHA256SUMS)
sha256sum "$APK"
nix develop -c bash -c 'exec "$ANDROID_HOME/build-tools/36.0.0/apksigner" "$@"' \
  android-tool verify --verbose --print-certs "$APK"
nix develop -c bash -c 'exec "$ANDROID_HOME/build-tools/36.0.0/aapt2" "$@"' \
  android-tool dump badging "$APK"
nix develop -c bash -c 'exec "$ANDROID_HOME/build-tools/36.0.0/aapt2" "$@"' \
  android-tool dump xmltree --file AndroidManifest.xml "$APK"
```

Require the APK checksum result to be `OK` and every field above to match;
inspect manifest flags as well as badging. A signature/package/hash mismatch
stops distribution of that artifact. Do not re-sign or weaken these checks.
For a future update, first select one real latest non-prerelease with
`gh api repos/trevarj/motd/releases/latest`, then freeze and verify its own values
and matching `changelogs/<versionCode>.txt`; the values above describe only v0.20.3.

For OpenAPK, follow the actual link from its
[download page](https://www.openapk.net/motd/io.github.trevarj.motd/apk/download),
not just catalogue metadata. If it lags, compare to its named upstream tag.
For Appteka, use the actual public APK after moderation. In either case compare
actual bytes/SHA-256 to the matching upstream APK, then run the same SDK commands
and require the certificate pin and package. Withhold badges on mismatch and
record the precise discrepancy. Generic catalogue x86/32-bit claims do not
change MOTD's arm64-only requirement.

## Maintainer checklist and Appteka updates

1. Use the prepared **untracked** packet/ZIP containing the verified APK and
   upstream `SHA256SUMS`, `FIELD-CHECKLIST.txt`, and `upload-description.txt`.
   Reuse `fastlane/metadata/android/en-US/{title.txt,short_description.txt,full_description.txt}`,
   `changelogs/20003.txt`, `images/icon.png`, and all three
   `images/phoneScreenshots/{01_chat-list.png,02_chat.png,03_file-uploader.png}`.
   The upload description is the existing full description with the selected
   [release source/license link](https://github.com/trevarj/motd/releases/tag/v0.20.3)
   appended; that release carries source archives, notices, and checksums.
   Do not commit the APK, ZIP, duplicated metadata, or credentials.
2. Before uploading, recheck the public pages above and current package/version
   listings in the Appteka app, including any newer or pending upload not exposed
   by public search. If **0.20.3 (20003)** is already public, verify that artifact
   and record its uploader instead of uploading a duplicate. Do not take over
   Silent Miqo's account or describe their older upload as a maintainer publication.
3. Install Appteka on **your own Android device**, authenticate with your own
   email/one-time code, and accept its live terms yourself. Never disclose an
   OTP/password through chat or agent tools; physical-device control is not assumed.
4. Transfer the packet to that device. Open the bottom **cloud/upload** screen,
   tap **Select Application**, and select the verified `motd-v0.20.3-foss.apk`.
5. Use these fields and the prepared description, icon, three screenshots, and
   current changelog:
   - Name **motd**; package **io.github.trevarj.motd**; version **0.20.3**, code **20003**.
   - Category **Communication** (observed ID 29); **Open source** enabled.
   - Source **https://github.com/trevarj/motd**; license **GPL-3.0-or-later**.
   - Minimum Android **8.0/API 26**, **arm64-v8a only**.
   - The existing **Exclusive** checkbox means “not available in Google Play,”
     not exclusive distribution; it can truthfully be enabled because MOTD has no Play listing.
6. Review the displayed package/version, then tap **Upload to Exchange**.
   Preserve the actual returned submission/share URL. A backend size rejection
   is a channel blocker: no public maximum was established for this 114 MB APK.
   No internal API/custom uploader or automatic publishing integration is used.
7. Wait for moderation, retrieve the actual public APK, and perform the upstream
   hash/certificate/package comparison above. Only after it passes, add a
   functioning availability badge to README, this runbook, and the site targeting
   the **actual current listing URL**. Use alt `Get motd on Appteka` and the client
   project's observed Shields style:
   `https://img.shields.io/badge/Appteka-Store-32A304?style=for-the-badge&logo=android`.
   It is not a store-verification seal or Appteka's own-app version badge.
   Pending login/moderation/verification gets status text, never a fake badge.
8. Keep README Install, this runbook, the docs index, and the installation page
   consistent. Build the static site with
   `OUT=$(mktemp -d /tmp/motd-store-site.XXXXXX); nix develop -c bash site/build.sh "$OUT"`;
   inspect 1280px/360px layouts, local badges, anchors, all new destinations and
   fallback instructions. Check the exact native Obtainium import on an owned
   emulator; do not install arm64-only MOTD on an x86 emulator or reset another device.
9. Before an authorized publication commit, inspect only the scoped public/staged
   files for secrets, run `trev-git-signing status`, and use `git commit -S`.
   If locked, the key owner must run `trev-git-signing unlock` on the key-owning
   desktop; never auto-unlock or commit unsigned. Push only the authorized docs/site
   change, not a new release/tag. The existing Pages workflow deploys `site/**`
   changes. Verify its real deployment and the live HTTPS Obtainium section/image/
   native link before declaring the README route published. Approve a protected
   Pages environment only if GitHub actually requests it.

**Outstanding maintainer action:** Recheck Appteka package versions, complete
native login/terms acceptance, transfer the prepared packet to your own Android
device, and upload; then await external moderation and verify the public APK.
No current Appteka publication is claimed. Use this same supported upload path for later
requested updates. After November 8, optional Obtainium catalogue submission
needs a fresh criteria/duplicate check; direct imports need no action now.

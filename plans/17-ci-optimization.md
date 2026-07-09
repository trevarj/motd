# 17 — CI optimization & Nix-caching evaluation

Analysis and changes to `.github/workflows/ci.yml` + `release.yml`. Versions,
AGP, and Gradle are pinned (plans/01) and were NOT touched.

## Bottleneck assessment (current CI, before this change)

Single `build` job on `ubuntu-latest` runs, in series:
`:irc:test :app:testDebugUnitTest` → `:app:lintDebug` → `:app:assembleDebug`.

Wall-clock drivers, roughly in order:

1. **Gradle dependency resolution / download** on a cold cache (Compose BOM,
   Hilt, Room, KSP, AGP, Kotlin, Robolectric jars). This is the biggest cold
   cost and is exactly what `gradle/actions/setup-gradle@v4` caches (Gradle
   user home: downloaded deps + wrapper dist + build cache).
2. **KSP + Kotlin/Compose compilation** of `:app` (Hilt + Room processors).
   CPU-bound; incremental across runs only via the Gradle build cache.
3. **Lint** (`:app:lintDebug`) — the intermittent
   `NoClassDefFoundError` in `ModifierDeclarationDetector` lives here; it is a
   Gradle-worker classloader race, not a real lint failure.
4. **Tests** (Robolectric pulls its runtime; `:irc` is trivially fast).
5. **Android SDK**: effectively free — `ubuntu-latest` ships a preinstalled SDK,
   so there is no SDK download at all.

Already cached: Gradle user home + Gradle build cache (via setup-gradle);
configuration-cache and build-cache are enabled in `gradle.properties`.
Not cached / not free: cold compilation output when the build cache misses (new
runners, cache eviction). The Android SDK does not need caching because it is
preinstalled.

## Nix-caching evaluation (the explicit ask)

Question: would moving CI to `nix develop` + a Nix binary cache be faster?

**Verdict: No. Keep `setup-java` + preinstalled SDK + Gradle cache.**

Reasoning:

- The flake's `androidenv.composeAndroidPackages` closure (SDK platform 35,
  build-tools 35.0.0, platform-tools, JDK 17) is large. On a cold run CI must
  *realize* that whole closure before Gradle even starts. The current path pays
  **zero** for the SDK because `ubuntu-latest` preinstalls it. Nix trades a free
  toolchain for a downloaded/cached one — strictly worse on the first run and,
  at best, a wash later.
- Binary-cache options and why none win here:
  - `DeterminateSystems/magic-nix-cache-action` — the hosted Magic Nix Cache
    service was **shut down (Feb 2025)**; not a viable dependency.
  - `cachix/cachix-action` — works, but needs an external Cachix account, an
    auth token secret, and a push step. Operational weight for a single-dev
    Android app with no Nix-built artifacts to share. Not worth it.
  - `nix-community/cache-nix-action` (Nix store in GitHub Actions cache) — the
    only self-contained option, but it stores the *entire* Android SDK closure
    in the **same 10 GB/repo GitHub cache budget** that the far-more-valuable
    Gradle cache already uses. They compete and evict each other, making both
    caches less reliable. Net negative for cache hit-rate.
- The recent flakiness (a hang and the lint classloader race) is **not** a
  toolchain-provisioning problem, so Nix would not fix it.

Hybrid considered and rejected: using Nix only for the pure-JVM `:irc` job.
Even there, realizing the JDK closure costs more than `setup-java`, which is
already fast and cached by GitHub. No upside.

Bottom line: Nix stays the canonical **local** dev env (flake.nix); CI stays on
`setup-java` + preinstalled SDK + `setup-gradle`, consistent with plans/08.

## Changes applied

`ci.yml`:

- **Split into two parallel jobs**: `irc` (pure-JVM `:irc:test`) and `app`
  (`:app:testDebugUnitTest` → lint → `:app:assembleDebug` + artifact). The fast
  irc feedback no longer waits behind, or is blocked by, a slow/hanging app
  build. Each job gets its own setup-gradle cache automatically.
- **`timeout-minutes`** on both jobs (irc 15, app 25) so a hang fails fast
  instead of burning the ~6h default and wedging the concurrency group. This
  directly addresses the ~15 min hang that had to be cancelled manually.
- **Lint hardened against the classloader race**: `:app:lintDebug` now runs
  `--no-daemon -Dorg.gradle.workers.max=1` (serializes the lint worker, killing
  the `ModifierDeclarationDetector` `NoClassDefFoundError` race) wrapped in a
  bounded 2-attempt retry as a belt-and-suspenders guard. Only the lint step
  retries; tests and assemble do not.
- Gradle caching left to setup-gradle defaults (read/write on `main`, read-only
  on PRs — no `cache-disabled`). Configuration-cache still enabled via
  `gradle.properties`; `--no-daemon` is compatible with it (cache is serialized
  to disk).

`release.yml`:

- **`timeout-minutes: 30`** added. Signing path untouched — keystore decode and
  all `MOTD_*` env stay exactly as before; still builds the signed release APK
  on `v*` tags.

Functional equivalence preserved: push/PR still runs `:irc:test` + app unit
tests + lint + `assembleDebug` and uploads the debug APK; tags still build the
signed APK.

## What to measure after pushing

- Wall-clock of the `irc` vs `app` jobs separately (confirm irc finishes early
  and the split actually parallelizes).
- Whether lint still ever throws `NoClassDefFoundError`; if attempt 1 keeps
  failing and attempt 2 saves it, the `--no-daemon`/single-worker fix is
  insufficient and lint should stay permanently single-worker (already is).
- Whether `--no-daemon` on lint measurably lengthens the app job (extra JVM
  startup + config-cache reload). If it costs more than the flake was worth,
  drop `--no-daemon` and keep only `-Dorg.gradle.workers.max=1` + retry.
- Gradle cache hit-rate on PRs (setup-gradle logs a cache report) to confirm the
  two jobs aren't thrashing the 10 GB budget.

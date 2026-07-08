# AGENTS.md — MOTD ground rules

Implementation agents: read `plans/00-overview.md` first, then the plan doc(s) for your work
package (`plans/09-work-packages.md`). These rules are absolute:

1. **Pinned versions are law.** Use `gradle/libs.versions.toml` exactly as specified in
   `plans/01-versions-gradle.md`. Never bump, downgrade, or add a dependency outside it.
2. **Contracts are frozen.** Cross-package types/interfaces are defined verbatim in
   `plans/10-contracts.md`. Implement against them; never edit them. If a contract seems wrong,
   stop and report instead of changing it.
3. **No kapt** — Hilt and Room via KSP only. **No minification** — `isMinifyEnabled = false`.
   **No OkHttp** — okio over `Socket`/`SSLSocket` for IRC, `HttpURLConnection` for previews.
4. **Directory ownership.** Each work package owns an exclusive set of directories
   (`plans/09-work-packages.md`). Never create or edit files outside your ownership.
5. `:irc` is pure JVM — zero Android imports. All protocol logic must be unit-testable with a
   fake transport.
6. Kotlin style: idiomatic coroutines/Flow, sealed hierarchies for events/state, brief intent
   comments. Compose: stateless composables + ViewModel state holders.
7. Verification before done: your WP's acceptance criteria checklist, plus
   `./gradlew build` compiles green.
8. **Local environment is the Nix flake** (host is Guix, but dev tooling comes from Nix):
   `nix develop -c ./gradlew ...`. Never suggest guix install/apt/brew or a global SDK.

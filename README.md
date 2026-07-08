# MOTD

A modern native Android IRCv3 client. Telegram-grade UI, IRC under the hood.

- Connects directly to networks (Libera.Chat) or through a [soju](https://soju.im) bouncer
- Bouncer features light up automatically: infinite scrollback (`draft/chathistory`),
  cross-device read state (`draft/read-marker`), multi-network over one account
  (`soju.im/bouncer-networks`), battery-friendly push (`soju.im/webpush` + UnifiedPush)
- Kotlin, Jetpack Compose, Material 3 with dynamic color

## Status

Design phase. Implementation blueprints live in [`plans/`](plans/).

## Building

CI is the canonical build environment (see `.github/workflows/`). Locally, the Nix flake
provides JDK 17 and the Android SDK (direnv loads it via `.envrc`):

```sh
nix develop -c ./gradlew :irc:test           # protocol engine tests (pure JVM)
nix develop -c ./gradlew :app:assembleDebug  # full APK build
```

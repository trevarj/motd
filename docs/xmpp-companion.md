# XMPP companion setup

motd can use the separate **motd XMPP Companion** app as a Labs provider. The
companion keeps XMPP credentials, connection state, OMEMO keys, trust decisions,
MAM, and uploads. motd talks to it through a paired Binder control channel and a
private IRCv3 stream over `ParcelFileDescriptor`.

This is a source-only prototype. There is no published APK or Maven artifact.

## Components

- `motd` provides chat UI, notifications, and local history.
- `motd-xmpp-sidecar` is the Conversations-derived management app and XMPP
  provider.
- `motd-sidecar-sdk` defines the public API, fake provider, and conformance
  helpers. Both apps pin it under `third_party/motd-sidecar-sdk`.

One enabled XMPP account becomes one motd network. XMPP credentials and OMEMO
keys never enter motd. Decrypted messages do cross private Android IPC and are
stored as plaintext in motd's Room history.

## Requirements

- Nix with flakes enabled.
- An arm64 Android device with USB debugging enabled. motd's normal debug APK
  includes an arm64-only libbox artifact.
- An existing XMPP JID and password.
- For the complete feature set, an XMPP server supporting MAM, PEP/OMEMO,
  carbons, stream management, MUC, and HTTP Upload.

Do not force-stop either app. Android blocks background delivery for
force-stopped apps. On restrictive devices, allow background activity and
exclude the companion from battery optimization so its XMPP connection remains
reachable.

## Build

Initialize pinned source in each consumer checkout:

```sh
cd motd
git submodule update --init --recursive

cd ../motd-xmpp-sidecar
git submodule update --init --recursive
```

Build motd:

```sh
cd ../motd
nix develop -c bash ./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Build the companion:

```sh
cd ../motd-xmpp-sidecar
nix develop -c bash ./gradlew assembleConversationsFreeDebug
```

Use the universal APK unless APK size matters:

```text
build/outputs/apk/conversationsFree/debug/*-conversations-free-universal-debug.apk
```

The SDK builds automatically as a composite build. To verify it separately:

```sh
cd ../motd-sidecar-sdk
nix develop -c bash ./gradlew testDebugUnitTest assembleDebug
```

## Install

Install or update both debug APKs. Installing with `-r` preserves app data and
pairing when the signing key is unchanged.

```sh
cd ../motd
nix develop -c adb install -r app/build/outputs/apk/debug/app-debug.apk

cd ../motd-xmpp-sidecar
PROVIDER_APK=$(find build/outputs/apk/conversationsFree/debug \
  -name '*-conversations-free-universal-debug.apk' -print -quit)
nix develop -c adb install -r "$PROVIDER_APK"
```

motd debug uses package `io.github.trevarj.motd.debug`; the companion uses
`io.github.trevarj.motd.xmpp`. A build signed by a different key must be paired
again.

## Configure the XMPP account

1. Open **motd XMPP Companion**.
2. Tap **Add XMPP account**.
3. Enter the existing full JID and password.
4. Save, then wait for the account row to report **ONLINE**.
5. Open account settings there for server connection details, OMEMO devices,
   trust decisions, or diagnostics. The companion intentionally has no launcher
   chat list or composer.

## Pair with motd

1. Open motd.
2. Open **Settings → Labs**.
3. Enable **Companion bridges**.
4. Tap **Manage companion providers**.
5. Tap **motd XMPP Companion**. Review the package and signer digest in the
   companion approval dialog, then approve it.
6. Back in motd, tap the XMPP account row. motd creates and connects one
   companion network for that account.

Use **New conversation** in motd to open the companion's contact/MUC picker.
Network settings expose **Manage in provider**; chat security details open the
provider-owned verification screen. The attachment sheet offers **Send with
companion provider**, which uses the companion's HTTP Upload and current OMEMO
policy.

## Runtime behavior

- The companion maps messages, MAM history, replies, reactions, retractions,
  typing, read markers, MUC presence, names, avatars, and encryption state onto
  IRCv3.
- OMEMO policy remains provider-owned. An encrypted conversation is never
  silently downgraded to plaintext.
- Incoming state is committed by the companion before it wakes motd. motd then
  reconnects and pulls history. If no valid wake token exists, companion
  notifications remain the fallback.
- Disabling **Companion bridges** disconnects companion networks and blocks new
  sends, but preserves network rows and local history.
- Message-edit mutation, calls, location, and multiple attachments are outside
  this prototype.

## Re-pair or remove

Tap a paired provider under **Settings → Labs → Manage companion providers** to
revoke it. motd disconnects its networks but preserves their rows and history.
Reinstalling the provider with an unrelated signing key also requires explicit
approval; motd will not bind using the old pin.

## Troubleshooting

- **No provider appears:** install the companion, enable Companion bridges, then
  use the refresh button. Confirm both APKs came from compatible API-level-1
  source.
- **No account appears after pairing:** open the companion and confirm the XMPP
  account is enabled and online.
- **Signing identity changed:** pair again only after confirming the installed
  APK is expected.
- **Messages arrive only after opening motd:** allow companion background
  operation and confirm it was not force-stopped.
- **OMEMO send is blocked:** open encryption/device verification from motd or
  the companion and resolve untrusted devices. motd does not override provider
  security policy.
- **Uploads fail:** verify server HTTP Upload support and inspect the companion's
  account/connection diagnostics.

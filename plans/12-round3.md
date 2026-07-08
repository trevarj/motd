# 12 — Round 3: TLS trust-on-first-use + simplified bouncer auth

Two user-requested features. Both land after round 2 (HEAD has lint-zero + per-network push).
Contract amendments in `10-contracts.md` §"Round 3 amendments"; keep the frozen-contract
discipline. Release v0.1.1 includes these.

## Feature 1 — TOFU TLS pinning (self-signed / bare-IP bouncers)

User decision: **trust on first use** — on an invalid/self-signed cert, prompt with the cert
details; on accept, **pin** the exact leaf (SHA-256) for that host:port and reconnect. If a
pinned cert later **changes**, block and **re-prompt** (warn: possible MITM / rotation).

Pinning the exact leaf fingerprint is strictly stronger than hostname/CA validation, so a
pinned connection safely skips hostname verification (required for bare-IP certs).

### `:irc` transport (additive amendment — safe)
`OkioLineTransport` gains `verifyHostname: Boolean = true`. When false, do NOT set
`endpointIdentificationAlgorithm` (skip hostname check); SNI still set via `createSocket(host)`.
`TransportFactory.create(host, port, tls)` is unchanged — the app factory decides per host and
passes the flag to the transport constructor.

### `:app` trust plumbing
- **CertTrustStore** (new contract, DataStore-backed): `pinnedFor(host, port): String?`,
  `isPinned(host, port, sha256): Boolean`, `pin(host, port, sha256)`,
  `unpin(host, port)`. Key `cert_pins` = JSON `{"host:port":"<sha256 hex>"}`.
- **PinningTrustManager** (`service/`, `X509TrustManager`) built per connection by
  `AppTransportFactory`, holding host/port + the pinned fingerprint (looked up at create()
  time via runBlocking, like STS already does):
  - `checkServerTrusted`: compute leaf SHA-256.
    - pinned exists & matches → accept (return).
    - pinned exists & differs → throw `CertUntrustedException(changed = true, details)`.
    - no pin → delegate to the platform default `X509TrustManager`; on its
      `CertificateException` → throw `CertUntrustedException(changed = false, details)`;
      on success → accept (normal CA-valid path; hostname still enforced because
      `verifyHostname = true` for unpinned hosts).
  - `CertUntrustedException(host, port, sha256, subject, issuer, notBefore, notAfter,
    changed)` extends `CertificateException` so it surfaces as the handshake failure cause.
- **AppTransportFactory.create**: always build an `SSLContext` for TLS now (not only for client
  certs) = optional KeyChain KeyManager (existing) + `PinningTrustManager`. Set
  `verifyHostname = pinnedFor(host, port) == null` (pinned → skip hostname; unpinned → enforce).

### Connection lifecycle (service)
- `ConnectionActor`: on connect failure, unwrap the cause chain; if `CertUntrustedException`,
  publish a `CertPrompt` on the manager and enter a quiescent "awaiting trust" state — do NOT
  backoff-loop (that would spam prompts). Any other failure keeps the existing backoff.
- **ConnectionManager** contract additions:
  - `val certPrompts: StateFlow<List<CertPrompt>>`
  - `suspend fun trustCert(prompt: CertPrompt)` — `certStore.pin(...)`, drop the prompt,
    reconnect that network.
  - `fun dismissCertPrompt(prompt: CertPrompt)` — drop the prompt; network stays disconnected.
  - `data class CertPrompt(networkId, host, port, sha256, subject, issuer, notBefore,
    notAfter, changed)`.

### UI — global dialog host (decouples from onboarding)
A `CertTrustDialog` (`ui/components/`) shown by a small host composable in `MainActivity`
(above the NavHost) observing `connectionManager.certPrompts`. Works everywhere — onboarding
connect-test, chat-list reconnect, etc. Shows host:port, formatted SHA-256, subject, issuer,
validity dates; `changed = true` → red "certificate changed" warning header. Buttons: Trust
(→ `trustCert`) / Cancel (→ `dismissCertPrompt`). New strings.

### Tests
Fingerprint match/mismatch/first-use decisions on `PinningTrustManager` with fabricated
`X509Certificate`s (or a pure decision helper `certDecision(pinned, presented): Trust|Prompt|Changed`);
`CertTrustStore` round-trip (pin, isPinned, changed detection).

## Feature 2 — simplified bouncer auth (onboarding)

soju logs in with SASL PLAIN (bouncer username + password). The onboarding AUTH step currently
offers a NONE/PLAIN/EXTERNAL picker for both paths. For the **soju** path, drop the picker:
show only **Username** + **Password**, always SASL PLAIN. The direct **NETWORK** path keeps
the full picker unchanged.

- `OnboardingReducer`: on `ChooseConnection(SOJU)`, set `auth = auth.copy(mode = PLAIN)`;
  `canAdvance` for AUTH on the soju path requires `saslUser`/`saslPassword` non-blank (already
  true via `AuthForm.isValid` for PLAIN). No new state.
- `OnboardingScreen` AUTH page: `if (state.isSoju)` render two fields (labels "Username" /
  "Password", bound to `saslUser`/`saslPassword`) with no mode radio and no EXTERNAL/NONE;
  else the existing picker. The soju `NetworkEntity` is built with `saslMechanism = "PLAIN"`.
- Reducer test: choosing SOJU forces PLAIN and AUTH validity needs both fields.

## Contract amendments (plans/10 §Round 3)
- `OkioLineTransport` + `verifyHostname: Boolean = true`.
- `ConnectionManager`: `certPrompts`, `trustCert`, `dismissCertPrompt`, `CertPrompt`.
- New `CertTrustStore` interface.
(`CertUntrustedException`, `PinningTrustManager` are `:app` service internals — not contracts.)

## Work packages (sequential to avoid strings.xml / di / MainActivity churn)
- **WP-S1 (TLS TOFU):** irc/transport/Transport.kt (verifyHostname), service/AppTransportFactory,
  service/ServiceSeam (contract), service/ConnectionManagerImpl + ConnectionActor, data/prefs
  (CertTrustStore + impl), di/AppModule (bind CertTrustStore), MainActivity (dialog host),
  ui/components/CertTrustDialog.kt, strings.xml, tests. Also amend plans/10.
- **WP-S2 (bouncer auth):** ui/onboarding/OnboardingReducer.kt + OnboardingScreen.kt, reducer
  test. (Runs after S1 to avoid strings.xml/onboarding overlap; S2 doesn't touch S1's files.)

## Verification
Full `nix develop -c ./gradlew build` green + `:app:lintDebug` 0/0 (warningsAsErrors) after
each WP. Manual: point a network at a self-signed/IP TLS bouncer → connect → dialog shows
fingerprint → Trust → connects; reconnect is silent; simulate cert change → warn+re-prompt.
Onboarding soju path shows only username/password.

## Risks
- Skipping hostname verification is scoped to *pinned* hosts only; unpinned hosts keep full
  CA + hostname validation, so Libera etc. are unaffected and still prompt-free.
- runBlocking pin lookup in `create()` mirrors the existing STS pattern (IO connect path).
- Prompt storms: actor parks in "awaiting trust" instead of backoff-looping on cert failures.
- Cert-change warning is the MITM guard; silently re-trusting was explicitly rejected.

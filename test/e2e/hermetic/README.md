# Hermetic e2e bouncer stack

A self-contained soju + ergo bouncer stack for running the MOTD device runbook
(`test/e2e/runbook.sh`) with **no external network and no secrets**. This is what
the `.github/workflows/e2e.yml` CI job stands up before booting the emulator.

## Topology

```
app (emulator)  --TLS 10.0.2.2:6697-->  soju  --plaintext ergo:6667-->  ergo
                                          |                               |
                                   bouncer network "libera"        upstream IRCd
                                   message-store db (CHATHISTORY)   hosts ##motdtest
```

- **ergo** (`ghcr.io/ergochat/ergo:v2.18.0`) — the upstream IRCd soju proxies.
  Plaintext `:6667`, internal to the compose network only. Enables the IRCv3
  caps the app uses (SASL, server-time, message-tags, batch, chathistory,
  echo-message, …) — all on by ergo default.
- **ergo-provision** — a one-shot container (`register` mode) that registers the
  ergo accounts (`motd` upstream account, `motdadb2` seed identity) and creates
  `##motdtest` with a topic. Runs **before** soju connects.
- **ergo-seed** — a one-shot container (`seed` mode) that posts a deterministic
  set of seed messages into `##motdtest` **after** soju is connected and joined,
  so soju observes them live and writes them to its message-store (this is what
  makes the app's CHATHISTORY backfill / search return content — soju only logs
  traffic it sees while connected).
- **soju** (built from `codeberg.org/emersion/soju` `v0.10.1`) — the app-facing
  bouncer. Presents a **self-signed TLS cert** on `:6697` (the app's TOFU
  `CertTrustDialog` trusts it on first connect). Proxies ergo as the bouncer
  network **`libera`** (the name Phase A imports). `message-store db` keeps
  history persistently so the app can page real backfilled messages.

The Android emulator reaches host-published ports at `10.0.2.2`, so the app
connects to `10.0.2.2:6697`. See `../.env.ci` for the matching runbook config.

## Credentials (ephemeral, local, not secrets)

| Where | User | Password | Notes |
| --- | --- | --- | --- |
| soju (app SASL login) | `motd` | `motdtest` | what the app enters in onboarding |
| ergo upstream account | `motd` | `motdupstream` | soju identifies to this via connect-command |
| seed identity | `motdadb2` | `motdadb2pass` | posts the seed history in `##motdtest` |
| app nick | `motdadb` | — | the nick the app registers |
| network name | `libera` | — | the bouncer network the app imports |
| channel | `##motdtest` | — | seed channel |

## Run the stack locally

Requires Docker + the compose plugin. On this Guix host neither is on the flake
yet — install/enable Docker out of band, or add `docker` + `docker-compose` to a
`guix shell` before running these:

```sh
# Build + start (soju is built from source the first time; ~1-2 min).
docker compose -f test/e2e/hermetic/docker-compose.yml up --build -d

# Watch provisioning + seed, then soju coming up:
docker compose -f test/e2e/hermetic/docker-compose.yml logs -f

# Confirm soju is healthy:
docker compose -f test/e2e/hermetic/docker-compose.yml ps

# Sanity-check the TLS endpoint the app will hit (from the host):
openssl s_client -connect 127.0.0.1:6697 </dev/null 2>/dev/null | head

# Tear down and WIPE all state (fresh accounts/history next run):
docker compose -f test/e2e/hermetic/docker-compose.yml down -v
```

## Drive the runbook against it locally

With the stack up and an emulator/device attached:

```sh
cp test/e2e/.env.ci test/e2e/.env          # host=10.0.2.2 for an emulator
# On a physical device, set MOTD_SOJU_HOST to the host machine's LAN IP instead.
nix develop -c ./test/e2e/runbook.sh
```

Phase A trusts the self-signed cert, imports `libera`, and finishes onboarding;
Phase C joins `##motdtest` and sees the seeded backfill.

## Re-seeding

The seed runs once at stack start (`ergo-seed`, after soju is healthy). To
re-post the messages without a full teardown (soju must be up so it observes
them):

```sh
docker compose -f test/e2e/hermetic/docker-compose.yml run --rm ergo-seed
```

Registration (`ergo-provision`) is idempotent (ergo replies "already
registered", which the script ignores); re-running `ergo-seed` appends the
messages again.

## Phase coverage (hermetic)

See `../HERMETIC_STATUS.md` for which runbook phases pass hermetically and which
need extra seed data. Green by design: **A** (onboard + TOFU + import `libera`),
**B** (drawer/scope), **C** (join + send + seeded history/search). **D–I** are
best-effort and may need more seeding (a second live member for typing/DM,
op privileges for moderation, an inline image URL the app can render).

## Version pins (bump deliberately)

| Component | Pin | Source |
| --- | --- | --- |
| ergo | `v2.18.0` | `ghcr.io/ergochat/ergo` |
| soju | `v0.10.1` | `codeberg.org/emersion/soju` (built in `soju/Dockerfile`) |
| Go builder (soju) | `golang:1.24-alpine3.21` | `soju/Dockerfile` |
| provisioner base | `alpine:3.21` | `ergo/Dockerfile.provision` |

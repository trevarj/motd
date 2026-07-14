# Hermetic E2E bouncer stack

This directory contains the Docker stack used by the fast headless and
shell-driven emulator workflows. For commands, environment variables, phase
coverage, and troubleshooting, use the canonical
[`../README.md`](../README.md).

## Topology

```text
Android emulator --TLS 10.0.2.2:6697--> soju --TCP ergo:6667--> ergo
                                           |                     |
                                    bouncer network          ##motdtest
                                    message-store DB         seed identity
```

- `ergo` is the private upstream IRCd.
- `ergo-provision` registers the upstream accounts and channel before soju
  connects.
- `soju` presents a self-signed TLS certificate, exposes the bouncer network
  named `libera`, and stores CHATHISTORY.
- `ergo-seed` posts deterministic traffic after soju joins so the messages are
  captured in its store.
- `ergo-member` then keeps the second fixture identity joined so member and nick
  actions have deterministic live state throughout a test run.

The published ports are test-only. Credentials in `.env.ci` and the Compose
configuration are ephemeral local fixtures and must not be reused elsewhere.

## Lifecycle

```sh
docker compose -f test/e2e/hermetic/docker-compose.yml up --build -d --wait
docker compose -f test/e2e/hermetic/docker-compose.yml logs --no-color
docker compose -f test/e2e/hermetic/docker-compose.yml down -v
```

Use `down -v` between clean runs so accounts, certificates, and message history
are recreated deterministically.

## Intentional pins

The Ergo container tag and soju source tag/Dockerfile are deliberately pinned in
this directory. Change them only with a stack boot, provisioning, seed, TLS, and
CHATHISTORY validation. The Android workflow definitions remain authoritative
for emulator API, architecture, and Gradle task selection.

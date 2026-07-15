# Codebase and E2E hardening implementation goal

Work through the prioritized fixes and investigation missions in
`plans/23-codebase-e2e-hardening-audit.md` on the isolated
`fix/codebase-e2e-hardening` branch.

## Objective

Implement the audit completely and safely, using current source, tests,
workflows, `AGENTS.md`, `ARCHITECTURE.md`, `.agents/testing.md`, and
`test/e2e/README.md` as the authoritative contracts. Preserve the audit's
dependency order and compatibility constraints while turning each ready item
into a focused, tested change and each investigation item into its required
evidence and decision record.

## Execution contract

1. Establish a clean baseline and inspect the relevant implementation, tests,
   callers, migrations, harnesses, and workflows before each mission.
2. Execute in dependency order: C1, C2, C3, D1, K1, T1, A1, A2, O1, S1, H1.
   C3 may proceed independently of C1-C2, and narrow independent missions may
   be reordered only when doing so does not weaken characterization or create
   avoidable conflicts.
3. For every correctness mission, first add or strengthen characterization or
   regression coverage, then implement the smallest coherent production
   change, and verify all acceptance criteria from the audit.
4. Keep `EventProcessor` as the sole external IRC-to-Room writer, keep `:irc`
   Android-free, preserve Room migrations and serialized preferences, retain
   the existing networking boundaries, and keep FOSS as the only active
   flavor.
5. Treat A2 and S1 as investigation-first missions. Produce the requested
   measurements, threat model, and decision records before making broader
   performance or security changes. Do not enable release minification or run
   physical-device performance work without fresh maintainer authorization.
6. Preserve existing E2E coverage and `headless-core` gating semantics. Do not
   add a new Soju/reconnect/VLESS journey or promote optional suites. Local
   verification is limited to unit/integration tests, lint, builds, shell
   validation, and non-device stack checks unless the maintainer explicitly
   authorizes emulator or physical-device E2E.
7. Run checks from `.agents/testing.md` after every coherent slice, expanding
   to the full release-parity FOSS command for cross-module or release-sensitive
   changes. Push candidate commits when CI evidence is required and require the
   complete CI gate to pass before considering the applicable mission done.
8. Keep changes reviewable and bisectable. Commit coherent completed slices
   with their tests, record verification and unresolved evidence, and never
   mix opportunistic cleanup into a mission.
9. Update `plans/23-codebase-e2e-hardening-audit.md` as missions are completed
   or findings change their scope, so the remaining work and dependencies stay
   explicit.
10. Continue until every mission is implemented and verified or is genuinely
    blocked by a documented decision, external authority, or reproducible
    technical constraint. Do not merge, tag, publish, install on a device, or
    cut a release unless separately requested.

## Completion criteria

- Every C1-H1 acceptance criterion is backed by code, tests, generated
  artifacts, CI/harness changes, or an explicit investigation record as
  appropriate.
- Focused checks and the final full FOSS release-parity suite pass; applicable
  shell and Docker Compose validation passes; required CI, including
  `headless-core`, is green for pushed candidates.
- The final branch is clean, its commits are narrowly scoped, compatibility and
  architectural invariants are intact, and the audit accurately records any
  intentionally deferred work with concrete reasons.

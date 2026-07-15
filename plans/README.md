# Historical design plans

The files in this directory record MOTD’s original architecture and staged
implementation. They are useful for design rationale and feature archaeology,
but they are not current implementation contracts.

For active work, use this authority order:

1. current user requirements and repository `AGENTS.md`;
2. source code, Gradle configuration, tests, scripts, and GitHub workflows;
3. current subsystem documentation, including `ARCHITECTURE.md`, `.agents/`,
   and `test/e2e/README.md`;
4. these historical plans for background only.

In particular, the original work-package ownership, frozen-contract rules,
dependency snippets, CI definitions, and E2E selector lists have been superseded.
Use Git history when the full text of a retired operational plan is needed.

## Current audit roadmaps

- [`23-codebase-e2e-hardening-audit.md`](23-codebase-e2e-hardening-audit.md) —
  prioritized correctness, consolidation, Android/Kotlin, and E2E-harness
  missions reviewed against `v0.7.1`.

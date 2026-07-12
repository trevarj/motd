# 18 — End-to-end UI runbook (historical pointer)

The original coverage and work-package plan has been retired. The executable
harness and its canonical operator documentation now live together:

- `test/e2e/README.md` — setup, modes, phase coverage, selectors, and diagnostics
- `test/e2e/runbook.sh` — current A–I traversal
- `test/e2e/lib.sh` — adb/uiautomator helper implementation
- `.github/workflows/smoke.yml` and `.github/workflows/e2e.yml` — current CI use

Git history retains the original matrix and implementation plan. Do not treat
its old selectors, remote credentials, work packages, or release-gating claims
as current requirements.

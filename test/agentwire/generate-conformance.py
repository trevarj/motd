#!/usr/bin/env python3
"""Import Agentwire's committed conformance inputs without a build-time Python dependency.

Usage:
  ./test/agentwire/generate-conformance.py --upstream ~/Workspace/agentwire \
      --revision <signed-agentwire-commit>
  ./test/agentwire/generate-conformance.py --upstream ~/Workspace/agentwire --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE = "agentwire/upstream.json"
FIXTURE_ROOT = "protocol/fixtures"
CORPUS_ROOT = "protocol/conformance"
SCHEMA = "protocol/agentwire-v1.schema.json"


def git(upstream: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(upstream), *args], text=True).strip()


def tracked_files(upstream: Path, revision: str) -> list[str]:
    names = git(upstream, "ls-tree", "-r", "--name-only", revision, "--", CORPUS_ROOT, FIXTURE_ROOT, SCHEMA)
    return [name for name in names.splitlines() if name]


def read_committed(upstream: Path, revision: str, source: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(upstream), "show", f"{revision}:{source}"])


def destination(source: str) -> Path | None:
    if source == SCHEMA:
        return ROOT / "irc/src/test/resources/agentwire/agentwire-v1.schema.json"
    if source.startswith(f"{CORPUS_ROOT}/") and source.endswith(".json"):
        return ROOT / "app/src/test/resources/agentwire/conformance" / Path(source).name
    if source.startswith(f"{FIXTURE_ROOT}/"):
        return ROOT / "irc/src/test/resources/agentwire/fixtures" / source.removeprefix(f"{FIXTURE_ROOT}/")
    return None


def is_clean(upstream: Path) -> bool:
    status = git(upstream, "status", "--porcelain", "--", CORPUS_ROOT, FIXTURE_ROOT, SCHEMA)
    return not status


def provenance(revision: str, copies: dict[str, bytes]) -> bytes:
    payload = {
        "format": 1,
        "upstreamCommit": revision,
        "files": {source: hashlib.sha256(content).hexdigest() for source, content in sorted(copies.items())},
    }
    return (json.dumps(payload, indent=2, sort_keys=True) + "\n").encode()


def expected(upstream: Path, revision: str) -> dict[Path, bytes]:
    copies = {
        source: read_committed(upstream, revision, source)
        for source in tracked_files(upstream, revision)
        if destination(source) is not None
    }
    targets = {destination(source): content for source, content in copies.items()}
    assert all(target is not None for target in targets)
    irc_copies = {
        source: content
        for source, content in copies.items()
        if source == SCHEMA or source.startswith(FIXTURE_ROOT)
    }
    app_copies = {source: content for source, content in copies.items() if source.startswith(CORPUS_ROOT)}
    targets[ROOT / "irc/src/test/resources" / PROVENANCE] = provenance(revision, irc_copies)
    targets[ROOT / "app/src/test/resources" / PROVENANCE] = provenance(revision, app_copies)
    return {target: content for target, content in targets.items() if target is not None}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--revision", help="exact committed Agentwire revision to import")
    parser.add_argument("--check", action="store_true", help="fail when copied resources are stale")
    args = parser.parse_args(argv)
    upstream = args.upstream.resolve()
    if not is_clean(upstream):
        parser.error("Agentwire protocol inputs are dirty; commit or discard them before importing")
    revision = args.revision
    if args.check and revision is None:
        local = ROOT / "irc/src/test/resources" / PROVENANCE
        try:
            revision = json.loads(local.read_text(encoding="utf-8"))["upstreamCommit"]
        except (KeyError, OSError, json.JSONDecodeError) as error:
            parser.error(f"cannot read imported provenance: {error}")
    if revision is None:
        parser.error("--revision is required when importing")
    try:
        revision = git(upstream, "rev-parse", "--verify", f"{revision}^{{commit}}")
    except subprocess.CalledProcessError:
        parser.error(f"unknown Agentwire commit: {revision}")

    targets = expected(upstream, revision)
    stale = [target for target, content in targets.items() if not target.is_file() or target.read_bytes() != content]
    if args.check:
        if stale:
            print("stale imported Agentwire resources: " + ", ".join(map(str, stale)), file=sys.stderr)
            return 1
        return 0
    for target, content in targets.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

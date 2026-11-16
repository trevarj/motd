#!/usr/bin/env python3
"""Run with: nix develop -c python3 tools/test-prepush.py (no builds or devices)."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

SOURCE = Path(__file__).resolve().parent
BASH = shutil.which("bash")
HEAD, OLD, OTHER, TAG, MISSING, ZERO = (c * 40 for c in "abcdef")
ZERO = "0" * 40

# Fake only external processes: the installed wrapper, hook, gate and classifier are real.
BOUNDARY = r'''
import json, os, pathlib, sys
root = pathlib.Path(os.environ["FIXTURE"])
s = json.loads((root / "state").read_text())
a = sys.argv[1:]
kind = pathlib.Path(sys.argv[0]).name
if kind == "git":
    while a[:1] == ["-C"]:
        os.chdir(a[1])
        a = a[2:]
with (root / "calls").open("a") as f:
    f.write(json.dumps([kind, a, {key: os.environ.get(key) for key in ("GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "MOTD_FUZZ_PROFILE", "MOTD_FUZZ_SEED")}, None, os.getcwd()]) + "\n")
if kind == "nix":
    if a == ["develop", "-c", "bash", "-c", 'printf "%s\\n" "$BASH"']:
        print(os.environ["TEST_BASH"])
    else:
        text = " ".join(a)
        if s.get("fail") and s["fail"] in text:
            sys.exit(23)
    sys.exit(0)
if a[0] == "check-ref-format":
    sys.exit(0 if a[1].startswith("refs/") and ".." not in a[1] and " " not in a[1] else 1)
if a[0] == "config":
    if "hooks_path" in s:
        print(s["hooks_path"])
        sys.exit(0)
    sys.exit(1)
if a[0] == "rev-parse":
    if "--show-toplevel" in a:
        print(os.getcwd())
        sys.exit(0)
    if "--git-path" in a:
        print(root / ".git/hooks/pre-push")
        sys.exit(0)
    ref = a[-1].removesuffix("^{commit}")
    checked = any(json.loads(line)[0] == "nix" for line in (root / "calls").read_text().splitlines())
    head = s["other"] if checked and s.get("head_after_checks") else s["head"]
    commits = {"HEAD": head, "origin/main": s["other"], s["head"]: s["head"], s["old"]: s["old"], s["other"]: s["other"], s["tag"]: s["head"]}
    if ref in commits:
        print(commits[ref])
        sys.exit(0)
    sys.exit(1)
if a[0] == "status":
    checked = any(json.loads(line)[0] == "nix" for line in (root / "calls").read_text().splitlines())
    print(" M tracked" if s.get("dirty") or (checked and s.get("dirty_after_checks")) else "", end="")
    sys.exit(0)
if a[0] == "diff":
    if s.get("diff_failure") and s["diff_failure"] in a:
        sys.exit(24)
    if "--name-only" in a:
        paths = s["paths"].get(a[-2], [])
        sys.stdout.buffer.write(b"".join(p.encode() + b"\0" for p in paths))
    sys.exit(0)
if a[0] == "ls-files":
    sys.exit(0)
raise SystemExit("Unexpected git call: " + repr(a))
'''


def main():
    with tempfile.TemporaryDirectory(prefix="motd-prepush-") as directory:
        root = Path(directory)
        (root / "tools").mkdir()
        (root / "bin").mkdir()
        for name in ("prepush.sh", "pre-push-hook.sh", "install-pre-push-hook.sh", "ci-paths.sh"):
            shutil.copy2(SOURCE / name, root / "tools" / name)
        for name in ("git", "nix"):
            path = root / "bin" / name
            path.write_text(f"#!{sys.executable}\n" + BOUNDARY)
            path.chmod(0o755)
        env = dict(os.environ, FIXTURE=str(root), TEST_BASH=BASH,
                   PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
        env.pop("MOTD_PREFLIGHT_BASE", None)
        env.update(GRADLE_OPTS='-Dorg.gradle.project.android.aapt2FromMavenOverride=/nix/sdk/aapt2 '
                   '"-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=2" '
                   '"-Dorg.gradle.project.kotlin.daemon.jvmargs=-Xmx4g -XX:ActiveProcessorCount=2"',
                   JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2")
        env.pop("MOTD_FUZZ_PROFILE", None)
        env.pop("MOTD_FUZZ_SEED", None)
        state = dict(head=HEAD, old=OLD, other=OTHER, tag=TAG,
                     paths={OLD: ["irc/src/test/Example.kt"], OTHER: ["README.md"]})

        def run(script, updates="", success=True):
            (root / "state").write_text(json.dumps(state))
            (root / "calls").write_text("")
            result = subprocess.run([BASH, str(root / "tools" / script)], input=updates,
                                    text=True, capture_output=True, env=env, cwd=root)
            assert (result.returncode == 0) == success, (result.returncode, result.stdout, result.stderr)
            calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
            return result, calls

        def update(local=HEAD, old=OLD, ref="refs/heads/main"):
            return f"{ref} {local} refs/heads/main {old}\n"

        def checks(calls):
            return [" ".join(call[1]) for call in calls if call[0] == "nix"]

        def light_gradle(calls, tasks):
            invocations = [call for call in calls if call[0] == "nix" and "./gradlew" in call[1]]
            assert len(invocations) == 1, checks(calls)
            call = invocations[0]
            args = call[1]
            options = args[args.index("./gradlew") + 1:]
            assert {arg for arg in options if not arg.startswith("-")} == set(tasks), options
            assert {"--no-daemon", "--no-parallel", "--max-workers=1",
                    "-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8",
                    "-Pkotlin.compiler.execution.strategy=in-process"} <= set(options), options
            launcher = args[:args.index("./gradlew")]
            assert {"env", "JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1", "bash"} <= set(launcher)
            assert call[2]["GRADLE_OPTS"] == env["GRADLE_OPTS"]
            assert call[2]["JAVA_TOOL_OPTIONS"] == "-XX:ActiveProcessorCount=2"
            assert all(call[2][key] is None for key in ("MOTD_FUZZ_PROFILE", "MOTD_FUZZ_SEED"))
            assert all(not any(banned in command for banned in
                              ("build-signed-release", "android-lint", "adb", "emulator"))
                       for command in checks(calls)), checks(calls)

        result, calls = run("pre-push-hook.sh", update())
        light_gradle(calls, {"ktlintCheck", ":irc:compileKotlin"})
        assert ["diff", "--name-only", "-z", OLD, HEAD] in [call[1] for call in calls]
        assert not any("..." in arg for call in calls for arg in call[1])
        assert any("python3 tools/palette-catalog.py --check --base " + OLD in command for command in checks(calls))
        assert any("python3 -m unittest discover -s tools -p test_palette_catalog.py" in command for command in checks(calls))
        for paths, tasks in (
            (["app/src/testDebug/Example.kt"], {"ktlintCheck", ":app:compileDebugKotlin"}),
            (["app/src/androidTest/Example.kt"], {"ktlintCheck", ":app:compileDebugKotlin"}),
            (["irc/src/main/Example.kt"], {"ktlintCheck", ":irc:compileKotlin", ":app:compileDebugKotlin"}),
            (["test/e2e/example.sh"], {"ktlintCheck"}),
        ):
            state["paths"][OLD] = paths
            result, calls = run("pre-push-hook.sh", update())
            light_gradle(calls, tasks)
        state["paths"][OLD] = ["palettes/index.json", "tools/palette-catalog.py", "tools/test_palette_catalog.py"]
        result, calls = run("pre-push-hook.sh", update())
        commands = checks(calls)
        assert len(commands) == 2, commands
        assert not any("gradlew" in command or "actionlint" in command or "validate.sh" in command for command in commands)
        for failure in ("tools/palette-catalog.py --check", "-m unittest discover -s tools -p test_palette_catalog.py"):
            state["fail"] = failure
            result, calls = run("pre-push-hook.sh", update(), success=False)
            assert result.returncode == 23
        del state["fail"]
        state["paths"][OLD] = ["irc/src/test/Example.kt"]

        state["fail"] = ":irc:compileKotlin"
        result, calls = run("pre-push-hook.sh", update(), success=False)
        assert result.returncode == 23
        del state["fail"]
        for prerequisite in ("--check", "--name-only"):
            state["diff_failure"] = prerequisite
            result, calls = run("pre-push-hook.sh", update(), success=False)
            assert not checks(calls)
        del state["diff_failure"]
        classifier = root / "tools/ci-paths.sh"
        classifier_source = classifier.read_text()
        for invalid in ("exit 25\n", "printf 'runtime=maybe\\n'\n"):
            classifier.write_text(invalid)
            result, calls = run("pre-push-hook.sh", update(), success=False)
            assert not checks(calls)
        classifier.write_text(classifier_source)
        result, calls = run("pre-push-hook.sh", update(local=OTHER), success=False)
        assert "check out pushed commit" in result.stderr and not checks(calls)
        state["dirty"] = True
        result, calls = run("pre-push-hook.sh", update(), success=False)
        assert "clean committed tree" in result.stderr and not checks(calls)
        del state["dirty"]
        result, calls = run("pre-push-hook.sh", f"(delete) {ZERO} refs/heads/old {OLD}\n")
        assert not checks(calls) and not any(call[1][0] == "status" for call in calls)
        for malformed in ("refs/heads/main garbage refs/heads/main garbage\n",
                          update().rstrip() + " extra\n"):
            result, calls = run("pre-push-hook.sh", malformed, success=False)
            assert not checks(calls)
        run("pre-push-hook.sh", update(local=TAG, ref="refs/tags/signed"))

        # Union all pushed deltas once, rather than checking only the first ref.
        state["paths"][OTHER] = ["app/src/debug/Example.kt"]
        result, calls = run("pre-push-hook.sh", update() + update(old=OTHER))
        light_gradle(calls, {"ktlintCheck", ":irc:compileKotlin", ":app:compileDebugKotlin"})
        catalog_checks = [call[1] for call in calls if call[0] == "nix" and "tools/palette-catalog.py" in call[1]]
        assert len(catalog_checks) == 1
        assert catalog_checks[0].count("--base") == 2
        assert set(catalog_checks[0][-4:]) == {"--base", OLD, OTHER}
        state["paths"][OTHER] = ["README.md"]

        required = ("tools/palette-catalog.py --check", "-m unittest discover -s tools -p test_palette_catalog.py",
                    ":irc:compileKotlin", "ktlintCheck", ":app:compileDebugKotlin", "actionlint", "validate.sh")
        for old in (ZERO, MISSING):
            result, calls = run("pre-push-hook.sh", update(old=old))
            commands = checks(calls)
            assert all(any(expected in command for command in commands) for expected in required), commands
            light_gradle(calls, {"ktlintCheck", ":irc:compileKotlin", ":app:compileDebugKotlin"})
        for failure in required:
            state["fail"] = failure
            result, calls = run("pre-push-hook.sh", update(old=ZERO), success=False)
            assert result.returncode == 23
        del state["fail"]
        for mutation in ("head_after_checks", "dirty_after_checks"):
            state[mutation] = True
            result, calls = run("pre-push-hook.sh", update(), success=False)
            assert "changed" in result.stderr
            del state[mutation]

        # Installer leaves custom configuration/hooks untouched and is idempotent.
        run("install-pre-push-hook.sh")
        hook = root / ".git/hooks/pre-push"
        installed = hook.read_bytes()
        assert installed.startswith(f"#!{BASH}\n".encode()) and os.access(hook, os.X_OK)
        run("install-pre-push-hook.sh")
        assert hook.read_bytes() == installed
        (root / "state").write_text(json.dumps(state))
        pushed = subprocess.run([str(hook), "origin", "unused"], input=update(), text=True,
                                capture_output=True, cwd=root, env=env)
        assert pushed.returncode == 0, pushed.stderr
        linked = root / "linked-worktree"
        shutil.copytree(root / "tools", linked / "tools")
        (root / "calls").write_text("")
        pushed = subprocess.run([str(hook), "origin", "unused"], input=update(), text=True,
                                capture_output=True, cwd=linked, env=env)
        assert pushed.returncode == 0, pushed.stderr
        calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
        assert checks(calls) and all(call[4] == str(linked) for call in calls if call[0] == "nix")
        state["fail"] = ":irc:compileKotlin"
        (root / "state").write_text(json.dumps(state))
        pushed = subprocess.run([str(hook), "origin", "unused"], input=update(), text=True,
                                capture_output=True, cwd=root, env=env)
        assert pushed.returncode != 0
        del state["fail"]
        hook.write_text("unknown hook\n")
        run("install-pre-push-hook.sh", success=False)
        assert hook.read_text() == "unknown hook\n"
        state["hooks_path"] = "/custom/hooks"
        run("install-pre-push-hook.sh", success=False)
        assert hook.read_text() == "unknown hook\n"
    print("pre-push hook, gate and installer regressions passed")


if __name__ == "__main__":
    main()

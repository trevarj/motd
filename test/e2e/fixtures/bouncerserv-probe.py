#!/usr/bin/env python3
"""Dependency-free BouncerServ proof against the pinned local soju fixture."""

from __future__ import annotations

import argparse
import base64
import re
import select
import socket
import ssl
import sys
import time
from dataclasses import dataclass, field


@dataclass
class Client:
    sock: socket.socket
    nick: str
    lines: list[str] = field(default_factory=list)
    pending: bytes = b""

    def send(self, line: str) -> None:
        self.sock.sendall((line + "\r\n").encode())

    def pump(self, seconds: float = 0.25) -> list[str]:
        started = time.monotonic()
        end = started + seconds
        added: list[str] = []
        while time.monotonic() < end:
            ready, _, _ = select.select([self.sock], [], [], max(0.0, end - time.monotonic()))
            if not ready:
                break
            chunk = self.sock.recv(65536)
            if not chunk:
                break
            self.pending += chunk
            while b"\n" in self.pending:
                raw, self.pending = self.pending.split(b"\n", 1)
                line = raw.rstrip(b"\r").decode(errors="replace")
                if line.startswith("PING "):
                    self.send("PONG " + line.split(" ", 1)[1])
                self.lines.append(line)
                added.append(line)
        return added

    def wait_for(self, predicate, label: str, seconds: float = 10.0) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines:
                if predicate(line):
                    return line
            self.pump(min(0.5, end - time.monotonic()))
        raise AssertionError(f"timed out waiting for {label}; tail:\n" + "\n".join(self.lines[-20:]))

    def command(self, command: str, seconds: float = 15.0, allow_silence: bool = False) -> list[str]:
        start = len(self.lines)
        self.send(f"PRIVMSG BouncerServ :{command}")
        started = time.monotonic()
        end = started + seconds
        replies: list[str] = []
        last_reply = 0.0
        reply_count = 0
        while time.monotonic() < end:
            self.pump(0.1)
            replies = [service_text(line) for line in self.lines[start:] if is_service_privmsg(line)]
            if len(replies) != reply_count:
                reply_count = len(replies)
                last_reply = time.monotonic()
            if replies:
                if time.monotonic() - last_reply >= 0.45:
                    return replies
            elif allow_silence and time.monotonic() - started >= 0.6:
                return []
        raise AssertionError(f"BouncerServ command timed out: {command!r}; replies={replies!r}")

    def close(self) -> None:
        try:
            self.send("QUIT :BouncerServ probe complete")
        except OSError:
            pass
        self.sock.close()


def is_service_privmsg(line: str) -> bool:
    return re.search(r"^:BouncerServ(?:!\S+)? PRIVMSG \S+ :", line, re.IGNORECASE) is not None


def service_text(line: str) -> str:
    return line.split(" :", 1)[1]


def connect(host: str, port: int, username: str, password: str, nick: str) -> Client:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    sock = context.wrap_socket(socket.create_connection((host, port), timeout=5), server_hostname=host)
    client = Client(sock, nick)
    client.send("CAP LS 302")
    client.send(f"NICK {nick}")
    client.send(f"USER {username} 0 * :MOTD BouncerServ probe")
    client.wait_for(lambda line: " CAP " in line and " LS " in line, "CAP LS")
    client.send("CAP REQ :sasl")
    client.wait_for(lambda line: " CAP " in line and " ACK " in line, "CAP ACK")
    client.send("AUTHENTICATE PLAIN")
    client.wait_for(lambda line: "AUTHENTICATE +" in line, "AUTHENTICATE +")
    payload = base64.b64encode(f"\0{username}\0{password}".encode()).decode()
    client.send("AUTHENTICATE " + payload)
    client.wait_for(lambda line: " 903 " in line, "SASL success")
    client.send("CAP END")
    client.wait_for(lambda line: " 001 " in line, "registration", 15)
    return client


def available(replies: list[str]) -> set[str]:
    marker = "available commands:"
    paths: set[str] = set()
    for reply in replies:
        index = reply.lower().find(marker)
        if index >= 0:
            paths.update(part.strip().lower() for part in reply[index + len(marker):].split(","))
    return paths


def require_reply(client: Client, command: str, needle: str) -> list[str]:
    replies = client.command(command)
    if not any(needle.lower() in reply.lower() for reply in replies):
        raise AssertionError(f"{command!r} did not include {needle!r}: {replies!r}")
    return replies


def smoke(host: str, port: int) -> None:
    admin = connect(host, port, "motd", "motdtest", "motdadminprobe")
    user = connect(host, port, "motduser", "motdusertest", "motduserprobe")
    suffix = str(time.time_ns())[-8:]
    network = "control" + suffix
    username = "probe" + suffix
    channel = "##control" + suffix
    debug_enabled = False
    try:
        admin_paths = available(admin.command("help"))
        user_paths = available(user.command("help"))
        for required in ("network create", "channel create", "user create", "server status"):
            if required not in admin_paths:
                raise AssertionError(f"admin help omitted {required!r}: {sorted(admin_paths)}")
        for forbidden in ("user create", "user status", "user run", "server status"):
            if forbidden in user_paths:
                raise AssertionError(f"non-admin help exposed {forbidden!r}: {sorted(user_paths)}")
        require_reply(user, "network status", "No network configured")

        require_reply(
            admin,
            f"network create -addr irc+insecure://127.0.0.1:6667 -name {network} -enabled false",
            "created network",
        )
        require_reply(admin, f"sasl set-plain -network {network} probe probe-pass", "credentials saved")
        require_reply(admin, f"sasl status -network {network}", "SASL PLAIN enabled")
        require_reply(admin, f"sasl reset -network {network}", "credentials reset")
        require_reply(admin, f"certfp generate -network {network} -key-type ed25519", "certificate generated")
        require_reply(admin, f"certfp fingerprint -network {network}", "SHA-256 fingerprint")

        require_reply(admin, f"channel create {channel}/libera -detached true", "created channel")
        require_reply(admin, "channel status -network libera", channel)
        require_reply(admin, f"channel update {channel}/libera -relay-detached highlight", "updated channel")
        require_reply(admin, f"channel delete {channel}/libera", "deleted channel")
        channel = ""

        require_reply(
            admin,
            f"user create -username {username} -password temporary -admin=false -enabled=true",
            "created user",
        )
        require_reply(admin, f"user update {username} -enabled false", "updated user")
        deletion = require_reply(admin, f"user delete {username}", "To confirm user deletion")
        match = re.search(rf'user delete {re.escape(username)} (\S+)"', " ".join(deletion))
        if not match:
            raise AssertionError(f"could not parse deletion token: {deletion!r}")
        require_reply(admin, f"user delete {username} {match.group(1)}", "deleted user")
        username = ""

        admin.command("server debug true", seconds=0.8, allow_silence=True)
        debug_enabled = True
        notice = "control-center-probe-" + suffix
        require_reply(admin, f"server notice '{notice}'", "sent to")
        user.wait_for(lambda line: " NOTICE " in line and notice in line, "broadcast NOTICE")
        admin.command("server debug false", seconds=0.8, allow_silence=True)
        debug_enabled = False

        require_reply(admin, f"network delete {network}", "deleted network")
        network = ""
        print("BOUNCERSERV_SMOKE_OK")
        print("admin_paths=" + " ".join(sorted(admin_paths)))
        print("nonadmin_paths=" + " ".join(sorted(user_paths)))
    finally:
        if debug_enabled:
            try:
                admin.command("server debug false", seconds=0.8, allow_silence=True)
            except (AssertionError, OSError):
                pass
        if channel:
            try:
                admin.command(f"channel delete {channel}/libera")
            except (AssertionError, OSError):
                pass
        if username:
            try:
                deletion = admin.command(f"user delete {username}")
                match = re.search(rf'user delete {re.escape(username)} (\S+)"', " ".join(deletion))
                if match:
                    admin.command(f"user delete {username} {match.group(1)}")
            except (AssertionError, OSError):
                pass
        if network:
            try:
                admin.command(f"network delete {network}")
            except (AssertionError, OSError):
                pass
        admin.close()
        user.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6697)
    args = parser.parse_args()
    smoke(args.host, args.port)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"BOUNCERSERV_PROBE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

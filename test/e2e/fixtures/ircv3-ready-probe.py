#!/usr/bin/env python3
"""Socket proof for ircv3-ready-server.py."""

from __future__ import annotations

import argparse
import base64
import select
import socket
import ssl
import sys
import time


class Client:
    def __init__(
        self,
        host: str,
        port: int,
        nick: str,
        tls: bool = False,
        username: str | None = None,
        password: str | None = None,
    ) -> None:
        sock = socket.create_connection((host, port), timeout=5)
        if tls:
            context = ssl.create_default_context()
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            sock = context.wrap_socket(sock, server_hostname=host)
        self.sock = sock
        self.nick = nick
        self.username = username
        self.password = password
        self.offered = ""
        self.offered_caps: set[str] = set()
        self.lines: list[str] = []
        self.pending = b""
        self.send("CAP LS 302")
        self.send(f"NICK {nick}")
        self.send(f"USER {nick} 0 * :Ready probe")

    def send(self, line: str) -> None:
        self.sock.sendall((line + "\r\n").encode())

    def pump(self, seconds: float = 0.25) -> None:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            ready, _, _ = select.select([self.sock], [], [], max(0, end - time.monotonic()))
            if not ready:
                return
            chunk = self.sock.recv(65536)
            if not chunk:
                raise OSError("fixture closed the connection")
            self.pending += chunk
            while b"\n" in self.pending:
                raw, self.pending = self.pending.split(b"\n", 1)
                self.lines.append(raw.rstrip(b"\r").decode(errors="replace"))

    def wait(self, text: str, start: int = 0, seconds: float = 5) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines[start:]:
                if text in line:
                    return line
            self.pump(min(0.25, end - time.monotonic()))
        raise AssertionError(f"missing {text!r}; tail:\n" + "\n".join(self.lines[-20:]))

    def register(self) -> None:
        ls = self.wait(" CAP * LS ")
        self.offered = ls
        self.offered_caps = {item.split("=", 1)[0] for item in ls.split(" :", 1)[-1].split()}
        for required in ("extended-monitor", "batch"):
            if required not in self.offered_caps:
                raise AssertionError(f"CAP LS omitted {required}: {ls}")
        lazy_alias = next(
            (
                alias
                for alias in (
                    "no-implicit-names",
                    "draft/no-implicit-names",
                    "soju.im/no-implicit-names",
                )
                if alias in self.offered_caps
            ),
            None,
        )
        if lazy_alias is None:
            raise AssertionError(f"CAP LS omitted every no-implicit-names alias: {ls}")
        requested = f"batch extended-monitor invite-notify message-tags {lazy_alias} server-time"
        if "userhost-in-names" in self.offered_caps:
            requested += " userhost-in-names"
        if self.username is not None:
            requested += " sasl"
        self.send(f"CAP REQ :{requested}")
        self.wait(" CAP * ACK ")
        if self.username is not None:
            self.send("AUTHENTICATE PLAIN")
            self.wait("AUTHENTICATE +")
            payload = base64.b64encode(
                f"\0{self.username}\0{self.password or ''}".encode()
            ).decode()
            self.send(f"AUTHENTICATE {payload}")
            self.wait(" 903 ")
        self.send("CAP END")
        self.wait(" 001 ")
        self.wait(" MONITOR=10 ")

    def close(self) -> None:
        try:
            self.send("QUIT :probe complete")
        except OSError:
            pass
        self.sock.close()


def smoke(
    host: str,
    port: int,
    tls: bool,
    username: str | None,
    password: str | None,
    skip_invite: bool,
) -> None:
    first = Client(host, port, "motdprobe", tls, username, password)
    second = None if skip_invite else Client(host, port, "invitee", tls, username, password)
    try:
        first.register()
        if second is not None:
            second.register()
        first.send("JOIN #ready")
        if not skip_invite:
            first.wait(" JOIN #ready")
        else:
            # The persistent soju upstream may already be joined from an earlier
            # probe, in which case it correctly suppresses a duplicate self-JOIN.
            first.pump(0.5)
        time.sleep(0.15)
        first.pump()
        if any(" 353 " in line for line in first.lines):
            raise AssertionError("fixture emitted implicit NAMES despite negotiated no-implicit-names")

        start = len(first.lines)
        first.send("NAMES #ready")
        names = first.wait(" 353 ", start)
        expected_member = (
            "@+readyfriend!fixture@ready.example"
            if "userhost-in-names" in first.offered_caps
            else "readyfriend"
        )
        if expected_member not in names:
            raise AssertionError(f"NAMES omitted expected member form {expected_member}: {names}")
        first.wait(" 366 ", start)

        start = len(first.lines)
        first.send("WHO #ready %tuhnafr,123")
        first.wait(" 123 fixture ready.example readyfriend readyaccount H@ ", start)
        first.wait(" #ready :End of WHO", start)

        start = len(first.lines)
        first.send("MONITOR C")
        first.send("MONITOR + readyfriend,offlinefriend")
        first.send("MONITOR S")
        first.wait("readyfriend!fixture@ready.example", start)
        first.wait("offlinefriend", start)
        if not skip_invite:
            first.wait(" :End of MONITOR list", start)

        if second is not None:
            start = len(second.lines)
            first.send("INVITE invitee #invited")
            second.wait(" INVITE invitee #invited", start)

        start = len(first.lines)
        first.send("PRIVMSG solanum.ready.test :READY-BATCHES")
        if skip_invite:
            # Pinned soju intentionally demonstrates unsupported degradation:
            # it strips the upstream batch wrappers but forwards both membership
            # mutations with their stable msgids.
            first.wait("msgid=split-1", start)
            first.wait(" QUIT :alpha.test beta.test", start)
            first.wait("msgid=join-1", start)
            first.wait(" JOIN #ready", start)
        else:
            first.wait(" BATCH +history chathistory #ready", start)
            first.wait(" BATCH +split netsplit alpha.test beta.test", start)
            first.wait("@batch=split;msgid=split-1 :readyfriend!fixture@ready.example QUIT", start)
            first.wait(" BATCH +join netjoin alpha.test beta.test", start)
            first.wait("@batch=join;msgid=join-1 :readyfriend!fixture@ready.example JOIN #ready", start)
            first.wait(" BATCH -history", start)
        print("IRCV3_READY_FIXTURE_OK" if not skip_invite else "IRCV3_READY_BOUNCER_OK")
    finally:
        first.close()
        if second is not None:
            second.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6671)
    parser.add_argument("--tls", action="store_true")
    parser.add_argument("--username")
    parser.add_argument("--password")
    parser.add_argument("--skip-invite", action="store_true")
    args = parser.parse_args()
    smoke(args.host, args.port, args.tls, args.username, args.password, args.skip_invite)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"IRCV3_READY_FIXTURE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

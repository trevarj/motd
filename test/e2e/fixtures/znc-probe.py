#!/usr/bin/env python3
"""Dependency-free protocol proof for the native ZNC fixture."""

from __future__ import annotations

import argparse
import base64
import select
import socket
import ssl
import sys
import time
from dataclasses import dataclass, field


CHANNEL = "##motdtest"


@dataclass
class Client:
    sock: socket.socket
    lines: list[str] = field(default_factory=list)
    pending: bytes = b""

    def send(self, line: str) -> None:
        self.sock.sendall((line + "\r\n").encode())

    def pump(self, seconds: float = 0.2) -> list[str]:
        end = time.monotonic() + seconds
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
                if " PING " in f" {line} " or line.startswith("PING "):
                    self.send("PONG " + line.split(" ", 1)[1])
                self.lines.append(line)
                added.append(line)
        return added

    def wait_for(self, needle: str, seconds: float = 10.0) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines:
                if needle in line:
                    return line
            self.pump(min(0.5, end - time.monotonic()))
        tail = "\n".join(self.lines[-20:])
        raise AssertionError(f"timed out waiting for {needle!r}; tail:\n{tail}")

    def close(self) -> None:
        try:
            self.send("QUIT :probe complete")
        except OSError:
            pass
        self.sock.close()


def tls_socket(host: str, port: int) -> socket.socket:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context.wrap_socket(socket.create_connection((host, port), timeout=5), server_hostname=host)


def cap_tokens(lines: list[str]) -> set[str]:
    tokens: set[str] = set()
    for line in lines:
        if " CAP " in line and " LS " in line:
            tokens.update(line.rsplit(":", 1)[-1].split())
    return tokens


def connect_znc(host: str, port: int, sasl: bool) -> Client:
    client = Client(tls_socket(host, port))
    client.send("CAP LS 302")
    if not sasl:
        client.send("PASS motd/libera:motdtest")
    # Attached ZNC clients share the upstream identity; distinct probe nicks would issue upstream
    # NICK changes rather than identify downstream sessions.
    client.send("NICK motdadb")
    client.send("USER motd 0 * :MOTD ZNC probe")
    client.wait_for(" CAP ")
    caps = cap_tokens(client.lines)
    requested = [cap for cap in ("server-time", "batch", "echo-message") if cap in caps]
    if sasl:
        if not any(cap == "sasl" or cap.startswith("sasl=") for cap in caps):
            raise AssertionError(f"ZNC did not advertise SASL: {sorted(caps)}")
        requested.append("sasl")
    if requested:
        client.send("CAP REQ :" + " ".join(requested))
        client.wait_for(" CAP ")
    if sasl:
        client.send("AUTHENTICATE PLAIN")
        client.wait_for("AUTHENTICATE +")
        auth = base64.b64encode(b"\0motd/libera\0motdtest").decode()
        client.send("AUTHENTICATE " + auth)
        client.wait_for(" 903 ")
    client.send("CAP END")
    client.wait_for(" 001 ", 15)
    client.wait_for(CHANNEL, 15)
    return client


def connect_ergo(port: int) -> Client:
    client = Client(socket.create_connection(("127.0.0.1", port), timeout=5))
    client.send("CAP LS 302")
    client.send("NICK motdadb2")
    client.send("USER motdadb2 0 * :MOTD direct probe")
    client.wait_for(" CAP ")
    client.send("CAP REQ :sasl echo-message server-time")
    client.wait_for(" CAP ")
    client.send("AUTHENTICATE PLAIN")
    client.wait_for("AUTHENTICATE +")
    auth = base64.b64encode(b"\0motdadb2\0motdadb2pass").decode()
    client.send("AUTHENTICATE " + auth)
    client.wait_for(" 903 ")
    client.send("CAP END")
    client.wait_for(" 001 ")
    client.send(f"JOIN {CHANNEL}")
    client.wait_for(" 366 ")
    return client


def observe_caps(host: str, port: int) -> list[str]:
    client = Client(tls_socket(host, port))
    try:
        client.send("CAP LS 302")
        client.wait_for(" CAP ")
        return sorted(cap_tokens(client.lines))
    finally:
        client.close()


def smoke(host: str, port: int, ergo_port: int) -> None:
    first = connect_znc(host, port, sasl=True)
    second = connect_znc(host, port, sasl=False)
    direct = connect_ergo(ergo_port)
    token = str(time.time_ns())
    live = f"znc-live-{token}"
    gap = f"znc-gap-{token}"
    query = f"znc-query-{token}"
    query_out = f"znc-query-out-{token}"
    try:
        first.send(f"PRIVMSG {CHANNEL} :{live}")
        second.wait_for(live)
        first.wait_for(live)
        # Soju is intentionally attached to the same Ergo account and may own the preferred nick,
        # so discover ZNC's actual upstream nick from an outgoing query instead of assuming it.
        first.send(f"PRIVMSG motdadb2 :{query_out}")
        outgoing = direct.wait_for(query_out)
        source = next(part for part in outgoing.split() if part.startswith(":"))
        upstream_nick = source.split("!", 1)[0].lstrip(":")
        direct.send(f"PRIVMSG {upstream_nick} :{query}")
        first.wait_for(query)
        second.wait_for(query)

        # ZNC 1.10.1 has a network-wide buffer cursor, not Cloak-style per-client unread state.
        # Detach every client before creating a gap; the next attach must receive native playback.
        second.close()
        first.close()
        time.sleep(1)
        direct.send(f"PRIVMSG {CHANNEL} :{gap}")
        direct.wait_for(gap)
        replay = connect_znc(host, port, sasl=False)
        try:
            replay_line = replay.wait_for(gap)
            if not replay_line.startswith("@") or "time=" not in replay_line:
                raise AssertionError(f"playback lacked server-time: {replay_line}")
        finally:
            replay.close()
    finally:
        first.close()
        direct.close()

    caps = observe_caps(host, port)
    if "draft/chathistory" in caps:
        raise AssertionError("fixture assumption changed: ZNC now advertises draft/chathistory")
    print("ZNC_SMOKE_OK")
    print("observed_caps=" + " ".join(caps))
    print("degradation=draft/chathistory absent; timestamped native playback available")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("caps", "smoke"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6698)
    parser.add_argument("--ergo-port", type=int, default=6667)
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.command == "caps":
        caps = observe_caps(args.host, args.port)
        rendered = " ".join(caps)
        print("observed_caps=" + rendered)
        print("draft/chathistory=" + ("present" if "draft/chathistory" in caps else "absent"))
        if args.output:
            with open(args.output, "w", encoding="utf-8") as handle:
                handle.write(rendered + "\n")
        return 0
    smoke(args.host, args.port, args.ergo_port)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"ZNC_PROBE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

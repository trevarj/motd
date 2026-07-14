#!/usr/bin/env python3
"""Two-client draft/read-marker proof against the local soju fixture."""

from __future__ import annotations

import argparse
import base64
import datetime
import select
import socket
import ssl
import sys
import time
from dataclasses import dataclass, field


@dataclass
class Client:
    sock: socket.socket
    lines: list[str] = field(default_factory=list)
    pending: bytes = b""

    def send(self, line: str) -> None:
        self.sock.sendall((line + "\r\n").encode())

    def pump(self, seconds: float = 0.25) -> None:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            ready, _, _ = select.select([self.sock], [], [], max(0.0, end - time.monotonic()))
            if not ready:
                return
            chunk = self.sock.recv(65536)
            if not chunk:
                raise OSError("server closed the connection")
            self.pending += chunk
            while b"\n" in self.pending:
                raw, self.pending = self.pending.split(b"\n", 1)
                line = raw.rstrip(b"\r").decode(errors="replace")
                if line.startswith("PING "):
                    self.send("PONG " + line.split(" ", 1)[1])
                self.lines.append(line)

    def wait_for(self, predicate, label: str, start: int = 0, seconds: float = 12.0) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines[start:]:
                if predicate(line):
                    return line
            self.pump(min(0.5, end - time.monotonic()))
        raise AssertionError(f"timed out waiting for {label}; tail:\n" + "\n".join(self.lines[-20:]))

    def close(self) -> None:
        try:
            self.send("QUIT :read-marker probe complete")
        except OSError:
            pass
        self.sock.close()


def connect(host: str, port: int, username: str, password: str, nick: str) -> Client:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    sock = context.wrap_socket(socket.create_connection((host, port), timeout=5), server_hostname=host)
    client = Client(sock)
    client.send("CAP LS 302")
    client.send(f"NICK {nick}")
    client.send(f"USER {nick} 0 * :MOTD read-marker probe")
    client.wait_for(lambda line: " CAP " in line and " LS " in line, "CAP LS")
    client.pump(0.5)
    offered = " ".join(line for line in client.lines if " CAP " in line and " LS " in line)
    if "draft/read-marker" not in offered:
        raise AssertionError(f"soju did not advertise draft/read-marker: {offered}")
    client.send("CAP REQ :sasl draft/read-marker server-time echo-message message-tags")
    ack = client.wait_for(lambda line: " CAP " in line and " ACK " in line, "CAP ACK")
    if "draft/read-marker" not in ack:
        raise AssertionError(f"soju did not ACK draft/read-marker: {ack}")
    client.send("AUTHENTICATE PLAIN")
    client.wait_for(lambda line: "AUTHENTICATE +" in line, "AUTHENTICATE +")
    payload = base64.b64encode(f"\0{username}\0{password}".encode()).decode()
    client.send("AUTHENTICATE " + payload)
    client.wait_for(lambda line: " 903 " in line, "SASL success")
    client.send("CAP END")
    client.wait_for(lambda line: " 001 " in line, "registration", seconds=15)
    return client


def tag(line: str, name: str) -> str | None:
    if not line.startswith("@"):
        return None
    for item in line[1:].split(" ", 1)[0].split(";"):
        key, separator, value = item.partition("=")
        if key == name:
            return value if separator else ""
    return None


def marker_value(line: str) -> str | None:
    parts = line.split(" :", 1)[0].split()
    for part in parts:
        if part.startswith("timestamp="):
            return part.removeprefix("timestamp=")
    return None


def is_marker(line: str, channel: str) -> bool:
    return " MARKREAD " in line and f" {channel} " in line and marker_value(line) is not None


def smoke(host: str, port: int, username: str, password: str, network: str, channel: str) -> None:
    authcid = f"{username}/{network}"
    first = connect(host, port, authcid, password, "motdmarker1")
    second = connect(host, port, authcid, password, "motdmarker2")
    reconnect: Client | None = None
    try:
        token = f"read-marker-{time.time_ns()}"
        first_start = len(first.lines)
        second_start = len(second.lines)
        first.send(f"PRIVMSG {channel} :{token}")
        echo = first.wait_for(
            lambda line: " PRIVMSG " in line and token in line,
            "timestamped self echo",
            start=first_start,
        )
        second.wait_for(
            lambda line: " PRIVMSG " in line and token in line,
            "message on second client",
            start=second_start,
        )
        timestamp = tag(echo, "time")
        if timestamp is None:
            raise AssertionError(f"self echo had no server-time tag: {echo}")

        first_start = len(first.lines)
        second_start = len(second.lines)
        first.send(f"MARKREAD {channel} timestamp={timestamp}")
        first_marker = first.wait_for(
            lambda line: is_marker(line, channel) and marker_value(line) == timestamp,
            "marker echo on first client",
            start=first_start,
        )
        second_marker = second.wait_for(
            lambda line: is_marker(line, channel) and marker_value(line) == timestamp,
            "marker broadcast on second client",
            start=second_start,
        )

        older = "1970-01-01T00:00:01.000Z"
        second.send(f"MARKREAD {channel} timestamp={older}")
        query_start = len(second.lines)
        second.send(f"MARKREAD {channel}")
        current = second.wait_for(
            lambda line: is_marker(line, channel),
            "current marker after stale SET",
            start=query_start,
        )
        if marker_value(current) != timestamp:
            raise AssertionError(f"marker regressed after stale SET: {current}")

        second.close()
        reconnect = connect(host, port, authcid, password, "motdmarker3")
        query_start = len(reconnect.lines)
        reconnect.send(f"MARKREAD {channel}")
        persisted = reconnect.wait_for(
            lambda line: is_marker(line, channel),
            "persisted marker after reconnect",
            start=query_start,
        )
        if marker_value(persisted) != timestamp:
            raise AssertionError(f"marker was not persisted across reconnect: {persisted}")

        print("READ_MARKER_SMOKE_OK")
        print("timestamp=" + timestamp)
        print("first=" + first_marker)
        print("second=" + second_marker)
    finally:
        first.close()
        try:
            second.close()
        except OSError:
            pass
        if reconnect is not None:
            reconnect.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6697)
    parser.add_argument("--username", default="motd")
    parser.add_argument("--password", default="motdtest")
    parser.add_argument("--network", default="libera")
    parser.add_argument("--channel", default="##motdtest")
    args = parser.parse_args()
    smoke(args.host, args.port, args.username, args.password, args.network, args.channel)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"READ_MARKER_PROBE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

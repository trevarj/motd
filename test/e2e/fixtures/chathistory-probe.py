#!/usr/bin/env python3
"""Prove retained Soju history is discoverable through CHATHISTORY.

The probe is intentionally dependency-free so local-stack.sh can run it both
directly against Soju and through its VLESS + REALITY SOCKS fixture.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import select
import socket
import ssl
import struct
import sys
import time
from dataclasses import dataclass, field
from typing import Callable


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

    def wait_for(
        self,
        predicate: Callable[[str], bool],
        label: str,
        start: int = 0,
        seconds: float = 12.0,
    ) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines[start:]:
                if " FAIL CHATHISTORY " in f" {line} ":
                    raise AssertionError(f"Soju rejected CHATHISTORY: {line}")
                if predicate(line):
                    return line
            self.pump(min(0.5, end - time.monotonic()))
        raise AssertionError(f"timed out waiting for {label}; tail:\n" + "\n".join(self.lines[-20:]))

    def close(self) -> None:
        try:
            self.send("QUIT :CHATHISTORY probe complete")
        except OSError:
            pass
        self.sock.close()


def recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise OSError("truncated SOCKS5 response")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def connect_socks(socks_host: str, socks_port: int, host: str, port: int) -> socket.socket:
    sock = socket.create_connection((socks_host, socks_port), timeout=10)
    sock.sendall(b"\x05\x01\x00")
    if recv_exact(sock, 2) != b"\x05\x00":
        raise OSError("SOCKS5 method negotiation rejected")

    encoded_host = host.encode("idna")
    if len(encoded_host) > 255:
        raise ValueError("SOCKS5 destination hostname is too long")
    request = b"\x05\x01\x00\x03" + bytes([len(encoded_host)]) + encoded_host + struct.pack(">H", port)
    sock.sendall(request)
    version, result, _, address_type = recv_exact(sock, 4)
    if version != 5 or result != 0:
        raise OSError(f"SOCKS5 CONNECT failed (version={version}, result={result})")
    if address_type == 1:
        recv_exact(sock, 6)
    elif address_type == 4:
        recv_exact(sock, 18)
    elif address_type == 3:
        recv_exact(sock, recv_exact(sock, 1)[0] + 2)
    else:
        raise OSError(f"unknown SOCKS5 reply address type {address_type}")
    return sock


def connect(
    host: str,
    port: int,
    username: str,
    password: str,
    network: str,
    socks_host: str | None,
    socks_port: int | None,
) -> Client:
    raw = (
        connect_socks(socks_host, socks_port, host, port)
        if socks_host is not None and socks_port is not None
        else socket.create_connection((host, port), timeout=10)
    )
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    client = Client(context.wrap_socket(raw, server_hostname=host))
    client.send("CAP LS 302")
    client.send("NICK motdhistoryprobe")
    client.send("USER motdhistoryprobe 0 * :MOTD CHATHISTORY probe")
    client.wait_for(lambda line: " CAP " in line and " LS " in line, "CAP LS")
    client.pump(0.25)
    offered = " ".join(line for line in client.lines if " CAP " in line and " LS " in line)
    if "draft/chathistory" not in offered:
        raise AssertionError(f"Soju did not advertise draft/chathistory: {offered}")
    client.send("CAP REQ :sasl batch draft/chathistory server-time message-tags")
    ack = client.wait_for(lambda line: " CAP " in line and " ACK " in line, "CAP ACK")
    if "draft/chathistory" not in ack:
        raise AssertionError(f"Soju did not ACK draft/chathistory: {ack}")
    client.send("AUTHENTICATE PLAIN")
    client.wait_for(lambda line: "AUTHENTICATE +" in line, "AUTHENTICATE +")
    authcid = f"{username}/{network}"
    payload = base64.b64encode(f"\0{authcid}\0{password}".encode()).decode()
    client.send("AUTHENTICATE " + payload)
    client.wait_for(lambda line: " 903 " in line, "SASL success")
    client.send("CAP END")
    client.wait_for(lambda line: " 001 " in line, "registration", seconds=15.0)
    return client


def timestamp(epoch: datetime.datetime) -> str:
    return epoch.strftime("%Y-%m-%dT%H:%M:%S.") + f"{epoch.microsecond // 1000:03d}Z"


def wait_for_batch_end(client: Client, start: int, label: str) -> None:
    client.wait_for(
        lambda line: line.startswith("BATCH -") or " BATCH -" in line,
        f"{label} batch end",
        start=start,
    )


def smoke(
    host: str,
    port: int,
    username: str,
    password: str,
    network: str,
    channel: str,
    seed_text: str,
    socks_host: str | None,
    socks_port: int | None,
) -> None:
    client = connect(host, port, username, password, network, socks_host, socks_port)
    try:
        start = len(client.lines)
        upper = timestamp(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=1))
        lower = "1970-01-01T00:00:00.000Z"
        client.send(f"CHATHISTORY TARGETS timestamp={upper} timestamp={lower} 100")
        target_line = client.wait_for(
            lambda line: " CHATHISTORY TARGETS " in line and channel.lower() in line.lower(),
            f"retained target {channel}",
            start=start,
        )
        wait_for_batch_end(client, start, "TARGETS")

        start = len(client.lines)
        client.send(f"CHATHISTORY LATEST {channel} * 100")
        message_line = client.wait_for(
            lambda line: seed_text in line,
            "seeded retained message",
            start=start,
        )
        wait_for_batch_end(client, start, "LATEST")

        print("CHATHISTORY_SMOKE_OK")
        print("target=" + target_line)
        print("message=" + message_line)
    finally:
        client.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6697)
    parser.add_argument("--username", default="motd")
    parser.add_argument("--password", default="motdtest")
    parser.add_argument("--network", default="libera")
    parser.add_argument("--channel", default="##motdtest")
    parser.add_argument("--seed-text", default="hello, this is a seeded plain line")
    parser.add_argument("--socks-host")
    parser.add_argument("--socks-port", type=int)
    args = parser.parse_args()
    if (args.socks_host is None) != (args.socks_port is None):
        parser.error("--socks-host and --socks-port must be used together")
    smoke(
        args.host,
        args.port,
        args.username,
        args.password,
        args.network,
        args.channel,
        args.seed_text,
        args.socks_host,
        args.socks_port,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, TimeoutError, ValueError) as error:
        print(f"CHATHISTORY_PROBE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

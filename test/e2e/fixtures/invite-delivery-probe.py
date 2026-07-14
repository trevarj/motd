#!/usr/bin/env python3
"""Prove a two-client INVITE traverses the local Ergo -> soju path."""

from __future__ import annotations

import argparse
import base64
import select
import socket
import ssl
import sys
import time


class Client:
    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock
        self.lines: list[str] = []
        self.pending = b""

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
                raise OSError("server closed the connection")
            self.pending += chunk
            while b"\n" in self.pending:
                raw, self.pending = self.pending.split(b"\n", 1)
                line = raw.rstrip(b"\r").decode(errors="replace")
                if line.startswith("PING "):
                    self.send("PONG " + line.split(" ", 1)[1])
                self.lines.append(line)

    def wait(self, text: str, start: int = 0, seconds: float = 10) -> str:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            for line in self.lines[start:]:
                if text in line:
                    return line
            self.pump(min(0.5, end - time.monotonic()))
        raise AssertionError(f"missing {text!r}; tail:\n" + "\n".join(self.lines[-20:]))

    def close(self) -> None:
        try:
            self.send("QUIT :invite probe complete")
        except OSError:
            pass
        self.sock.close()


def soju_client(host: str, port: int, username: str, password: str) -> Client:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    sock = context.wrap_socket(socket.create_connection((host, port), timeout=5), server_hostname=host)
    client = Client(sock)
    client.send("CAP LS 302")
    client.send("NICK motdinvite")
    client.send("USER motdinvite 0 * :Invite target")
    ls = client.wait(" CAP * LS ")
    if "invite-notify" not in ls:
        raise AssertionError(f"soju omitted invite-notify: {ls}")
    client.send("CAP REQ :invite-notify message-tags server-time sasl")
    client.wait(" CAP * ACK ")
    client.send("AUTHENTICATE PLAIN")
    client.wait("AUTHENTICATE +")
    payload = base64.b64encode(f"\0{username}\0{password}".encode()).decode()
    client.send(f"AUTHENTICATE {payload}")
    client.wait(" 903 ")
    client.send("CAP END")
    client.wait(" 001 ")
    return client


def direct_inviter(host: str, port: int, channel: str, target: str) -> Client:
    client = Client(socket.create_connection((host, port), timeout=5))
    client.send("NICK inviteprobe")
    client.send("USER inviteprobe 0 * :Invite sender")
    client.wait(" 001 ")
    client.send(f"JOIN {channel}")
    client.wait(f" JOIN {channel}")
    client.send(f"INVITE {target} {channel}")
    client.wait(" 341 ")
    return client


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--ergo-port", type=int, default=6667)
    parser.add_argument("--soju-port", type=int, default=6697)
    parser.add_argument("--username", default="motd/libera")
    parser.add_argument("--password", default="motdtest")
    parser.add_argument("--target", default="motdadb")
    parser.add_argument("--channel", default="##motdinvite")
    args = parser.parse_args()
    target = soju_client(args.host, args.soju_port, args.username, args.password)
    inviter = None
    try:
        start = len(target.lines)
        inviter = direct_inviter(args.host, args.ergo_port, args.channel, args.target)
        line = target.wait(f" INVITE {args.target} {args.channel}", start)
        print("INVITE_SOJU_DELIVERY_OK")
        print(line)
    finally:
        target.close()
        if inviter is not None:
            inviter.close()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError) as error:
        print(f"INVITE_DELIVERY_PROBE_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

#!/usr/bin/env python3
"""Small plaintext IRC server for physical draft/metadata-2 avatar checks."""

from __future__ import annotations

import argparse
import socketserver
import threading
from pathlib import Path


SERVER = "avatar.test"
CHANNEL = "#avatars"
REMOTE_NICK = "avatarfriend"
CAPABILITIES = (
    "batch cap-notify echo-message message-tags server-time "
    "draft/metadata-2=max-subs=16,max-keys=16,max-value-bytes=2048"
)


class State:
    def __init__(self, log_path: Path, avatar_url: str) -> None:
        self.log_path = log_path
        self.avatar_url = avatar_url
        self.lock = threading.Lock()

    def log(self, direction: str, line: str) -> None:
        with self.lock:
            with self.log_path.open("a", encoding="utf-8") as output:
                output.write(f"{direction} {line}\n")


class AvatarIrcHandler(socketserver.StreamRequestHandler):
    state: State

    def setup(self) -> None:
        super().setup()
        self.nick = "motd"
        self.have_user = False
        self.cap_ended = False
        self.registered = False

    def send(self, line: str) -> None:
        self.state.log("S", line)
        self.wfile.write(f"{line}\r\n".encode())
        self.wfile.flush()

    def maybe_register(self) -> None:
        if self.registered or not self.have_user or not self.cap_ended:
            return
        self.registered = True
        self.send(f":{SERVER} 001 {self.nick} :Welcome to the avatar metadata fixture")
        self.send(f":{SERVER} 005 {self.nick} CASEMAPPING=rfc1459 CHANTYPES=# :are supported")
        self.send(f":{SERVER} 376 {self.nick} :End of MOTD")
        self.send(f":{self.nick}!fixture@localhost JOIN {CHANNEL}")
        self.send(f":{REMOTE_NICK}!fixture@localhost JOIN {CHANNEL}")
        self.send(f":{SERVER} 353 {self.nick} = {CHANNEL} :{self.nick} {REMOTE_NICK}")
        self.send(f":{SERVER} 366 {self.nick} {CHANNEL} :End of NAMES")
        self.send(f":{REMOTE_NICK}!fixture@localhost PRIVMSG {CHANNEL} :Avatar metadata fixture")

    def send_remote_avatar(self, target: str = REMOTE_NICK) -> None:
        self.send(f":{SERVER} 761 {self.nick} {target} avatar * :{self.state.avatar_url}")

    def handle_metadata(self, params: list[str]) -> None:
        if len(params) >= 3 and params[0] == "*" and params[1] == "SUB" and params[2] == "avatar":
            self.send(f":{SERVER} 770 {self.nick} avatar :subscribed")
            self.send_remote_avatar()
            return
        if len(params) >= 2 and params[1] == "SYNC":
            self.send_remote_avatar()
            self.send(f":{SERVER} 762 {self.nick} {params[0]} :end of metadata")
            return
        if len(params) >= 3 and params[0] == "*" and params[1] == "SET" and params[2] == "avatar":
            value = params[3] if len(params) >= 4 else ""
            self.send(f":{SERVER} METADATA {self.nick} avatar * :{value}")

    def handle(self) -> None:
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        self.state.log("I", f"connected {peer}")
        try:
            for raw in self.rfile:
                line = raw.decode(errors="replace").rstrip("\r\n")
                self.state.log("C", line)
                if not line:
                    continue
                command, _, trailing = line.partition(" ")
                upper = command.upper()
                params = trailing.split()
                if upper == "CAP" and params and params[0].upper() == "LS":
                    self.send(f":{SERVER} CAP * LS :{CAPABILITIES}")
                elif upper == "CAP" and params and params[0].upper() == "REQ":
                    requested = trailing.split(":", 1)[-1]
                    self.send(f":{SERVER} CAP * ACK :{requested}")
                elif upper == "CAP" and params and params[0].upper() == "END":
                    self.cap_ended = True
                    self.maybe_register()
                elif upper == "NICK" and params:
                    self.nick = params[0].lstrip(":")
                    self.maybe_register()
                elif upper == "USER":
                    self.have_user = True
                    self.maybe_register()
                elif upper == "PING":
                    self.send(f"PONG :{trailing.lstrip(':')}")
                elif upper == "METADATA":
                    self.handle_metadata(params)
                elif upper == "JOIN" and params:
                    channel = params[0].lstrip(":")
                    self.send(f":{self.nick}!fixture@localhost JOIN {channel}")
                elif upper == "PRIVMSG" and len(params) >= 2:
                    target = params[0]
                    body = trailing.split(" :", 1)[-1]
                    self.send(f":{self.nick}!fixture@localhost PRIVMSG {target} :{body}")
        finally:
            self.state.log("I", f"disconnected {peer}")


class ThreadingIrcServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6670)
    parser.add_argument("--log", type=Path, default=Path("/tmp/motd-avatar-metadata.log"))
    parser.add_argument(
        "--avatar-url",
        default="https://avatars.githubusercontent.com/u/9919?s={size}",
    )
    args = parser.parse_args()
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text("", encoding="utf-8")
    state = State(args.log, args.avatar_url)
    AvatarIrcHandler.state = state
    with ThreadingIrcServer((args.host, args.port), AvatarIrcHandler) as server:
        state.log("I", f"listening {args.host}:{args.port}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

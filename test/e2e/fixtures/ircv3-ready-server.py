#!/usr/bin/env python3
"""Deterministic Solanum-shaped IRCv3 fixture for the Ready gap plan.

The server deliberately withholds implicit NAMES, answers an explicit NAMES +
WHOX refresh, implements a small MONITOR set, forwards two-client INVITEs, and
emits nested netsplit/netjoin batches. It is plaintext and intended only for an
adb-reversed local test connection.
"""

from __future__ import annotations

import argparse
import socketserver
import threading
from pathlib import Path


SERVER = "solanum.ready.test"
CHANNEL = "#ready"
REMOTE = "readyfriend"
SECOND_REMOTE = "splitfriend"
CAPABILITIES = (
    "batch cap-notify extended-monitor invite-notify message-tags "
    "no-implicit-names server-time userhost-in-names"
)


class State:
    def __init__(self, log_path: Path) -> None:
        self.log_path = log_path
        self.lock = threading.Lock()
        self.clients: dict[str, "ReadyHandler"] = {}

    def log(self, direction: str, line: str) -> None:
        with self.lock:
            with self.log_path.open("a", encoding="utf-8") as output:
                output.write(f"{direction} {line}\n")

    def rename(self, old: str | None, new: str, client: "ReadyHandler") -> None:
        with self.lock:
            if old is not None:
                self.clients.pop(old.casefold(), None)
            self.clients[new.casefold()] = client

    def remove(self, nick: str | None, client: "ReadyHandler") -> None:
        if nick is None:
            return
        with self.lock:
            if self.clients.get(nick.casefold()) is client:
                self.clients.pop(nick.casefold(), None)

    def target(self, nick: str) -> "ReadyHandler | None":
        with self.lock:
            return self.clients.get(nick.casefold())


class ReadyHandler(socketserver.StreamRequestHandler):
    state: State

    def setup(self) -> None:
        super().setup()
        self.nick: str | None = None
        self.have_user = False
        self.cap_ended = False
        self.registered = False
        self.enabled_caps: set[str] = set()
        self.monitored: list[str] = []

    def send(self, line: str) -> None:
        self.state.log("S", line)
        self.wfile.write(f"{line}\r\n".encode())
        self.wfile.flush()

    def maybe_register(self) -> None:
        if self.registered or self.nick is None or not self.have_user or not self.cap_ended:
            return
        self.registered = True
        self.send(f":{SERVER} 001 {self.nick} :Welcome to the IRCv3 Ready fixture")
        self.send(
            f":{SERVER} 005 {self.nick} CASEMAPPING=rfc1459 CHANTYPES=# "
            "CHANMODES=b,k,l,imnpst MONITOR=10 PREFIX=(ov)@+ WHOX :are supported"
        )
        self.send(f":{SERVER} 376 {self.nick} :End of MOTD")

    def names(self, channel: str) -> None:
        nick = self.nick or "motd"
        self.send(
            f":{SERVER} 353 {nick} = {channel} "
            f":@{nick}!device@localhost @+{REMOTE}!fixture@ready.example"
        )
        self.send(f":{SERVER} 366 {nick} {channel} :End of NAMES")

    def whox(self, mask: str, fields: str) -> None:
        nick = self.nick or "motd"
        token = fields.rsplit(",", 1)[-1] if "," in fields else "0"
        self.send(
            f":{SERVER} 354 {nick} {token} fixture ready.example {REMOTE} "
            f"readyaccount H@ :Ready Fixture User"
        )
        self.send(f":{SERVER} 315 {nick} {mask} :End of WHO")

    def monitor(self, params: list[str]) -> None:
        nick = self.nick or "motd"
        operation = params[0].upper() if params else ""
        targets = [item for item in " ".join(params[1:]).lstrip(":").split(",") if item]
        if operation == "C":
            self.monitored.clear()
        elif operation == "+":
            for target in targets:
                if target not in self.monitored:
                    self.monitored.append(target)
            online = [target for target in targets if target.casefold() == REMOTE.casefold()]
            offline = [target for target in targets if target.casefold() != REMOTE.casefold()]
            if online:
                masks = ",".join(f"{target}!fixture@ready.example" for target in online)
                self.send(f":{SERVER} 730 {nick} :{masks}")
            if offline:
                self.send(f":{SERVER} 731 {nick} :{','.join(offline)}")
        elif operation == "-":
            removed = {target.casefold() for target in targets}
            self.monitored = [item for item in self.monitored if item.casefold() not in removed]
        elif operation == "S":
            if self.monitored:
                self.send(f":{SERVER} 732 {nick} :{','.join(self.monitored)}")
            self.send(f":{SERVER} 733 {nick} :End of MONITOR list")

    def network_batches(self, channel: str) -> None:
        nick = self.nick or "motd"
        self.send(f":{SERVER} BATCH +history chathistory {channel}")
        self.send(f"@batch=history :{SERVER} BATCH +split netsplit alpha.test beta.test")
        self.send(f"@batch=split;msgid=split-1 :{REMOTE}!fixture@ready.example QUIT :alpha.test beta.test")
        self.send(
            f"@batch=split;msgid=split-2 :{SECOND_REMOTE}!fixture@ready.example "
            "QUIT :alpha.test beta.test"
        )
        self.send(f"@batch=history :{SERVER} BATCH -split")
        self.send(f"@batch=history :{SERVER} BATCH +join netjoin alpha.test beta.test")
        self.send(
            f"@batch=join;msgid=join-1 :{REMOTE}!fixture@ready.example "
            f"JOIN {channel} readyaccount :Ready Fixture User"
        )
        self.send(
            f"@batch=join;msgid=join-2 :{SECOND_REMOTE}!fixture@ready.example "
            f"JOIN {channel} splitaccount :Split Fixture User"
        )
        self.send(f"@batch=history :{SERVER} BATCH -join")
        self.send(f":{SERVER} BATCH -history")
        self.send(f":{REMOTE}!fixture@ready.example PRIVMSG {channel} :{nick}: batches complete")

    def handle(self) -> None:
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        self.state.log("I", f"connected {peer}")
        try:
            for raw in self.rfile:
                line = raw.decode(errors="replace").rstrip("\r\n")
                self.state.log("C", line)
                if not line:
                    continue
                command, _, rest = line.partition(" ")
                upper = command.upper()
                params = rest.split()
                if upper == "CAP" and params and params[0].upper() == "LS":
                    self.send(f":{SERVER} CAP * LS :{CAPABILITIES}")
                elif upper == "CAP" and params and params[0].upper() == "REQ":
                    requested = rest.split(":", 1)[-1].split()
                    accepted = [cap for cap in requested if cap in CAPABILITIES.split()]
                    self.enabled_caps.update(accepted)
                    self.send(f":{SERVER} CAP * ACK :{' '.join(accepted)}")
                elif upper == "CAP" and params and params[0].upper() == "END":
                    self.cap_ended = True
                    self.maybe_register()
                elif upper == "NICK" and params:
                    old = self.nick
                    self.nick = params[0].lstrip(":")
                    self.state.rename(old, self.nick, self)
                    self.maybe_register()
                elif upper == "USER":
                    self.have_user = True
                    self.maybe_register()
                elif upper == "PING":
                    self.send(f"PONG :{rest.lstrip(':')}")
                elif upper == "JOIN" and params:
                    channel = params[0].lstrip(":")
                    self.send(f":{self.nick}!device@localhost JOIN {channel} * :MOTD device")
                    self.send(
                        f":{REMOTE}!fixture@ready.example JOIN {channel} "
                        ":Ready Fixture User"
                    )
                    # no-implicit-names: no 353/366 appears until explicit NAMES.
                elif upper == "NAMES" and params:
                    self.names(params[0].lstrip(":"))
                elif upper == "WHO" and len(params) >= 2:
                    self.whox(params[0], params[1])
                elif upper == "MONITOR":
                    self.monitor(params)
                elif upper == "INVITE" and len(params) >= 2:
                    target = params[0]
                    channel = params[1].lstrip(":")
                    recipient = self.state.target(target)
                    if recipient is not None:
                        recipient.send(f":{self.nick}!inviter@ready.example INVITE {target} {channel}")
                    self.send(f":{SERVER} 341 {self.nick} {target} {channel}")
                elif upper == "PRIVMSG" and params and params[0] == SERVER:
                    body = params[-1].lstrip(":")
                    if body == "READY-BATCHES":
                        self.network_batches(CHANNEL)
                elif upper == "QUIT":
                    break
        finally:
            self.state.remove(self.nick, self)
            self.state.log("I", f"disconnected {peer}")


class ThreadingServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6671)
    parser.add_argument("--log", type=Path, default=Path("/tmp/motd-ircv3-ready.log"))
    args = parser.parse_args()
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text("", encoding="utf-8")
    state = State(args.log)
    ReadyHandler.state = state
    with ThreadingServer((args.host, args.port), ReadyHandler) as server:
        state.log("I", f"listening {args.host}:{args.port}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

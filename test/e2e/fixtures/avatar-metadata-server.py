#!/usr/bin/env python3
"""Legacy avatar smoke or two-client IRCv3 metadata profile fixture."""

from __future__ import annotations

import argparse
import contextlib
import http.server
import re
import socket
import socketserver
import ssl
import struct
import sys
import threading
import zlib
from pathlib import Path


SERVER = "avatar.test"
CHANNEL = "#avatars"
REMOTE_NICK = "avatarfriend"
CAPABILITIES = (
    "batch cap-notify echo-message message-tags server-time "
    "draft/metadata-2=max-subs=16,max-keys=16,max-value-bytes=2048"
)
PROFILE_KEYS = ("avatar", "bot", "color", "display-name", "homepage", "pronouns", "status")
CHANNEL_KEYS = ("avatar", "display-name")
PREFIX = {"q": "!", "o": "@", "v": "+"}
CONTROL_NICK = "fixturepeer"
KEY_NAME = re.compile(r"[a-z0-9_./-]{1,32}")
IRC_NAME = re.compile(r"(?:#[^ ,:\x00-\x20]{1,48}|[a-zA-Z_][a-zA-Z0-9_{}\[\]\\|^-]{0,29})")


def avatar_png(path: str) -> bytes:
    def chunk(name: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))

    background = (23, 105, 145) if path == "/self.png" else (153, 61, 31)
    rows = bytearray()
    for y in range(48):
        rows.append(0)
        for x in range(48):
            foreground = (x - 24) ** 2 + (y - 17) ** 2 < 8 ** 2 or (x - 24) ** 2 + (y - 41) ** 2 < 16 ** 2
            rows.extend((255, 223, 137) if foreground else background)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 48, 48, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


def canonical(name: str) -> str:
    return name.lower().translate(str.maketrans("[]\\^", "{}|~"))


def parse(line: str) -> tuple[dict[str, str], str, list[str]]:
    tags = {}
    if line.startswith("@"):
        tag_text, _, line = line[1:].partition(" ")
        tags = dict((tag.partition("=")[0], tag.partition("=")[2]) for tag in tag_text.split(";"))
    middle, marker, trailing = line.partition(" :")
    command, *params = middle.split() or [""]
    return tags, command.upper(), params + ([trailing] if marker else [])


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
    @property
    def state(self) -> State:
        return self.server.state

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
        self.send(f":{SERVER} 376 {self.nick} :End of motd")
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


class ProfileState(State):
    def __init__(self, args: argparse.Namespace, tls: ssl.SSLContext | None) -> None:
        super().__init__(args.log, args.avatar_url)
        self.lock = threading.RLock()
        self.version = args.profile
        self.cap = f"draft/metadata-{self.version}"
        limits = "max-subs=16,max-keys=16,max-value-bytes=2048"
        if self.version == 3:
            limits += ",max-key-bytes=32"
        self.caps = f"batch echo-message message-tags labeled-response multi-prefix {self.cap}={limits}"
        self.tls = tls
        self.operator = canonical(args.operator)
        self.endpoint = args.endpoint
        self.channels: dict[str, str] = {}
        self.clients: dict[str, ProfileIrcHandler] = {}
        self.values: dict[tuple[str, str], str] = {}
        self.reject_next: tuple[str, str] | None = None
        self.batch = 0


class ProfileIrcHandler(AvatarIrcHandler):
    def setup(self) -> None:
        super().setup()
        self.nick = "*"
        self.caps: set[str] = set()
        self.subs: set[str] = set()
        self.rooms: dict[str, set[str]] = {}
        self.label = None

    @property
    def who(self) -> str:
        return f":{self.nick}!fixture@localhost"

    @property
    def metadata_aware(self) -> bool:
        return {"batch", self.state.cap} <= self.caps

    def send(self, line: str) -> None:
        try:
            self.wfile.write(f"{line}\r\n".encode())
        except OSError:
            return
        self.state.log(f"S[{self.nick}]", line)

    def response(self, line: str) -> None:
        if self.label:
            line = f"@label={self.label} {line}"
        self.send(line)

    def numeric(self, code: int, text: str) -> str:
        return f":{SERVER} {code:03d} {self.nick} {text}"

    def fail(self, code: str, context: str, reason: str) -> None:
        self.response(f":{SERVER} FAIL METADATA {code} {context} :{reason}")

    def batch(self, kind: str, lines: list[str], target: str = "") -> None:
        self.state.batch += 1
        batch = f"m{self.state.batch}"
        self.response(f":{SERVER} BATCH +{batch} {kind}" + (f" {target}" if target else ""))
        for line in lines:
            self.send(f"@batch={batch} {line}")
        self.send(f":{SERVER} BATCH -{batch}")

    def target(self, name: str) -> str | None:
        if name == "*":
            return self.nick
        key = canonical(name)
        if key in self.state.channels:
            return self.state.channels[key]
        if key in self.state.clients:
            return self.state.clients[key].nick
        return None

    def visible(self, target: str) -> bool:
        key = canonical(target)
        if key.startswith("#"):
            return key in self.rooms
        return key == canonical(self.nick) or bool(self.rooms.keys() & self.state.clients[key].rooms.keys())

    def value_reply(self, target: str, key: str, value: str | None) -> str:
        if value is None:
            return self.numeric(766, f"{target} {key} :Key not set")
        return self.numeric(761, f"{target} {key} * :{value}")

    def notification(self, target: str, key: str, value: str | None) -> str:
        if self.state.version == 3:
            return self.value_reply(target, key, value)
        suffix = f" :{value}" if value is not None else ""
        return f":{SERVER} METADATA {target} {key} *{suffix}"

    def snapshot(self, target: str, registration: bool = False) -> None:
        key = canonical(target)
        if target == "*ALL":
            targets = [
                name for name in (*self.state.channels, *self.state.clients) if self.visible(name)
            ]
        elif key.startswith("#"):
            targets = [key, *(nick for nick, peer in self.state.clients.items() if key in peer.rooms)]
        else:
            targets = [key]
        lines = [
            self.notification(self.target(name), field, value)
            for name in targets
            for (owner, field), value in sorted(self.state.values.items())
            if owner == name and (registration or field in self.subs)
        ]
        self.batch("metadata", lines, target)

    def maybe_register(self) -> None:
        if self.registered or self.nick == "*" or not self.have_user or not self.cap_ended:
            return
        self.registered = True
        self.state.clients[canonical(self.nick)] = self
        self.send(self.numeric(1, ":Welcome to the profile metadata fixture"))
        self.send(self.numeric(5, "CASEMAPPING=rfc1459 CHANTYPES=# PREFIX=(qov)!@+ CHANMODES=b,k,l,imnst :are supported"))
        if self.metadata_aware:
            self.snapshot(self.nick, registration=True)
        self.send(self.numeric(376, ":End of MOTD"))

    def peers(self, room: str):
        return (peer for peer in self.state.clients.values() if room in peer.rooms)

    def names(self, room: str) -> None:
        if room in self.rooms:
            entries = []
            for peer in self.peers(room):
                marks = "".join(mark for mode, mark in PREFIX.items() if mode in peer.rooms[room])
                entries.append((marks if "multi-prefix" in self.caps else marks[:1]) + peer.nick)
            self.send(self.numeric(353, f"= {self.state.channels[room]} :{' '.join(entries)}"))
        self.send(self.numeric(366, f"{self.state.channels.get(room, room)} :End of NAMES"))

    def join(self, channel: str) -> None:
        room = canonical(channel)
        if room in self.rooms or not channel.startswith("#") or not IRC_NAME.fullmatch(channel):
            return
        channel = self.state.channels.setdefault(room, channel)
        self.rooms[room] = {"o"} if canonical(self.nick) == self.state.operator else set()
        for peer in self.peers(room):
            (self.response if peer is self else peer.send)(f"{self.who} JOIN {channel}")
        self.names(room)
        if self.metadata_aware:
            label, self.label = self.label, None
            self.snapshot(channel)
            self.label = label

    def mode(self, params: list[str]) -> None:
        room = canonical(params[0])
        if room not in self.state.channels:
            return
        channel = self.state.channels[room]
        if len(params) == 1:
            self.response(self.numeric(324, f"{channel} +nt"))
            return
        if canonical(self.nick) != CONTROL_NICK and not self.rooms.get(room, set()) & {"q", "o"}:
            self.response(self.numeric(482, f"{channel} :You're not channel operator"))
            return
        enabled = True
        nicks = iter(params[2:])
        for mode in params[1]:
            if mode in "+-":
                enabled = mode == "+"
            elif mode in PREFIX:
                peer = self.state.clients.get(canonical(next(nicks, "")))
                if peer and room in peer.rooms:
                    (peer.rooms[room].add if enabled else peer.rooms[room].discard)(mode)
                    for watcher in self.peers(room):
                        watcher.send(f"{self.who} MODE {channel} {'+' if enabled else '-'}{mode} {peer.nick}")

    def handle_metadata(self, params: list[str]) -> None:
        if not self.metadata_aware:
            return
        if len(params) < 2:
            self.fail("INVALID_PARAMS", "*", "Expected a target and subcommand")
            return
        raw, operation, *args = params
        operation = operation.upper()
        invalid_key = "INVALID_KEY" if self.state.version == 3 else "KEY_INVALID"
        if raw == "*" and operation in {"SUB", "UNSUB"} and args:
            valid = [key for key in args if KEY_NAME.fullmatch(key)]
            for key in args:
                if key not in valid:
                    self.fail(invalid_key, key, "Invalid key")
            if valid:
                if operation == "SUB":
                    self.subs.update(valid)
                    self.response(self.numeric(770, " ".join(valid)))
                    for (owner, key), value in sorted(self.state.values.items()):
                        target = self.target(owner)
                        if target and self.visible(target) and key in valid:
                            self.send(self.notification(target, key, value))
                else:
                    self.subs.difference_update(valid)
                    self.response(self.numeric(771, " ".join(valid)))
            return
        if raw == "*" and operation == "SUBS":
            self.batch("metadata-subs", [self.numeric(772, key) for key in sorted(self.subs)])
            return
        target = raw if raw == "*ALL" and operation == "SYNC" else self.target(raw)
        if target is None or (target != "*ALL" and not self.visible(target)):
            self.fail("INVALID_TARGET", raw, "Invalid metadata target")
            return
        owner = canonical(target)
        if operation == "SYNC" and not args:
            self.snapshot(target)
        elif (operation == "GET" and args) or (operation == "LIST" and not args):
            keys = args if operation == "GET" else sorted(key for name, key in self.state.values if name == owner)
            severity = "WARN" if self.state.version == 3 else "FAIL"
            self.batch(
                "metadata",
                [
                    self.value_reply(target, field, self.state.values.get((owner, field)))
                    if KEY_NAME.fullmatch(field) else f":{SERVER} {severity} METADATA {invalid_key} {field} :Invalid key"
                    for field in keys
                ],
                target,
            )
        elif operation == "SET" and len(args) in (1, 2):
            field = args[0]
            allowed = field in PROFILE_KEYS and owner == canonical(self.nick)
            if owner.startswith("#"):
                allowed = field in CHANNEL_KEYS and bool(self.rooms.get(owner, set()) & {"q", "o"})
            if not KEY_NAME.fullmatch(field):
                self.fail(invalid_key, field, "Invalid key")
                return
            if allowed and self.state.reject_next == (owner, field):
                self.state.reject_next = None
                allowed = False
            if not allowed:
                self.fail("KEY_NO_PERMISSION", f"{target} {field}", "Permission denied")
                return
            value = args[1] if len(args) == 2 else None
            entry = owner, field
            if value is None:
                if self.state.values.pop(entry, None) is None and self.state.version == 2:
                    self.fail("KEY_NOT_SET", f"{target} {field}", "Key not set")
                    return
            elif len(value.encode()) > 2048:
                self.fail("INVALID_VALUE" if self.state.version == 3 else "VALUE_INVALID", field, "Value too large")
                return
            else:
                self.state.values[entry] = value
            self.response(self.value_reply(target, field, value))
            for peer in self.state.clients.values():
                if peer is not self and peer.metadata_aware and field in peer.subs and peer.visible(target):
                    peer.send(peer.notification(target, field, value))
        else:
            self.fail("INVALID_PARAMS", operation, "Invalid parameters")

    def handle_command(self, command: str, params: list[str]) -> None:
        if command == "CAP" and params:
            operation = params[0].upper()
            if operation == "LS":
                self.send(f":{SERVER} CAP {self.nick} LS :{self.state.caps}")
            elif operation == "REQ" and len(params) == 2:
                caps = self.state.caps.split()
                offered = {cap.split("=")[0] for cap in caps}
                requested = set(params[1].split())
                if {cap.lstrip("-") for cap in requested} <= offered:
                    self.caps.update(cap for cap in requested if not cap.startswith("-"))
                    self.caps.difference_update(cap[1:] for cap in requested if cap.startswith("-"))
                    if not self.metadata_aware:
                        self.subs.clear()
                    self.send(f":{SERVER} CAP {self.nick} ACK :{params[1]}")
                else:
                    self.send(f":{SERVER} CAP {self.nick} NAK :{params[1]}")
            elif operation == "END":
                self.cap_ended = True
                self.maybe_register()
        elif command == "NICK" and params and IRC_NAME.fullmatch(params[0]) and not params[0].startswith("#"):
            nick = params[0]
            if canonical(nick) in self.state.clients and self.state.clients[canonical(nick)] is not self:
                self.send(self.numeric(433, f"{nick} :Nickname is already in use"))
            elif not self.registered:
                self.nick = nick
                self.maybe_register()
        elif command == "USER":
            self.have_user = True
            self.maybe_register()
        elif command == "PING" and params:
            self.response(f":{SERVER} PONG {SERVER} :{params[-1]}")
        elif self.registered and params:
            if command == "JOIN":
                for channel in params[0].split(","):
                    self.join(channel)
            elif command == "PART":
                room = canonical(params[0])
                if room in self.rooms:
                    for peer in self.peers(room):
                        peer.send(f"{self.who} PART {self.state.channels[room]}")
                    del self.rooms[room]
            elif command == "NAMES":
                self.names(canonical(params[0]))
            elif command == "WHO":
                room = canonical(params[0])
                for peer in self.peers(room):
                    flags = "H" + "".join(mark for mode, mark in PREFIX.items() if mode in peer.rooms[room])
                    self.send(self.numeric(352, f"{params[0]} fixture localhost {SERVER} {peer.nick} {flags} :0 Profile fixture"))
                self.send(self.numeric(315, f"{params[0]} :End of WHO"))
            elif command == "WHOIS":
                nick = params[-1]
                if nick and self.target(nick) and not nick.startswith(("#", "*")):
                    self.send(self.numeric(311, f"{nick} fixture localhost * :Profile fixture"))
                    self.send(self.numeric(312, f"{nick} {SERVER} :Profile fixture"))
                self.response(self.numeric(318, f"{nick} :End of WHOIS"))
            elif command == "MODE":
                self.mode(params)
            elif command == "METADATA":
                self.handle_metadata(params)
            elif command == "FIXTURE" and canonical(self.nick) == CONTROL_NICK and len(params) == 3:
                action, target, key = params
                if action.upper() == "REJECT" and self.target(target) and key in PROFILE_KEYS:
                    self.state.reject_next = canonical(self.target(target)), key
                    self.send(f":{SERVER} NOTICE {self.nick} :Rejecting next SET for {target} {key}")
            elif command == "PRIVMSG" and len(params) == 2:
                target, body = params
                room = canonical(target)
                recipients = list(self.peers(room)) if room in self.rooms else [self.state.clients.get(room)]
                if "echo-message" in self.caps and self not in recipients:
                    recipients.append(self)
                for peer in recipients:
                    if peer and (peer is not self or "echo-message" in self.caps):
                        peer.send(f"{self.who} PRIVMSG {target} :{body}")

    def handle(self) -> None:
        try:
            for raw in self.rfile:
                line = raw.decode(errors="replace").rstrip("\r\n")
                if not line.strip():
                    continue
                with self.state.lock:
                    self.state.log(f"C[{self.nick}]", line)
                    tags, command, params = parse(line)
                    if command == "QUIT":
                        break
                    self.label = tags.get("label") if "labeled-response" in self.caps else None
                    self.handle_command(command, params)
        except OSError:
            pass
        finally:
            with self.state.lock:
                if self.registered:
                    del self.state.clients[canonical(self.nick)]
                    for peer in self.state.clients.values():
                        if peer.rooms.keys() & self.rooms.keys():
                            peer.send(f"{self.who} QUIT :Fixture disconnected")


class AvatarHttpHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        endpoint = self.server.state.endpoint
        if self.headers.get("Host") not in (endpoint, f"{endpoint}:443") or any(
            name in self.headers for name in ("Authorization", "Proxy-Authorization", "Cookie")
        ):
            self.send_error(400)
            return
        path = self.path.partition("?")[0]
        if path not in ("/self.png", "/channel.png"):
            self.send_error(404)
            return
        png = avatar_png(path)
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(png)))
        self.end_headers()
        self.wfile.write(png)

    def log_message(self, message: str, *args: object) -> None:
        self.server.state.log("HTTPS", message % args)


class FixtureSocksHandler(socketserver.StreamRequestHandler):
    """Two fixed destinations; never resolves or forwards to arbitrary hosts."""

    def handle(self) -> None:
        self.connection.settimeout(10)
        greeting = self.rfile.read(2)
        if len(greeting) != 2 or greeting[0] != 5 or 0 not in self.rfile.read(greeting[1]):
            return
        self.wfile.write(b"\x05\x00")
        if self.rfile.read(4) != b"\x05\x01\x00\x03":
            return
        size = self.rfile.read(1)
        if not size:
            return
        host = self.rfile.read(size[0]).decode("ascii", errors="replace")
        port = int.from_bytes(self.rfile.read(2), "big")
        irc = self.server.irc
        state = irc.state
        irc_port = irc.server_address[1]
        if host != state.endpoint or port not in (443, irc_port):
            self.wfile.write(b"\x05\x02\x00\x01\x7f\x00\x00\x01\x00\x00")
            return
        self.wfile.write(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
        state.log("SOCKS", f"{host}:{port}")
        try:
            with state.tls.wrap_socket(self.connection, server_side=True) as secure:
                secure.settimeout(10 if port == 443 else None)
                if port == 443:
                    AvatarHttpHandler(secure, self.client_address, irc)
                else:
                    irc.RequestHandlerClass(secure, self.client_address, irc)
        except OSError:
            pass


class ThreadingIrcServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def finish_request(self, request: socket.socket, client_address) -> None:
        tls = getattr(getattr(self, "state", None), "tls", None)
        if tls is None:
            super().finish_request(request, client_address)
            return
        request.settimeout(10)
        try:
            with tls.wrap_socket(request, server_side=True) as secure:
                secure.settimeout(None)
                super().finish_request(secure, client_address)
        except OSError:
            pass


def peer_console(args: argparse.Namespace) -> None:
    with contextlib.ExitStack() as stack:
        connection = stack.enter_context(socket.create_connection((args.host, args.port), timeout=10))
        if args.tls_cert:
            tls = ssl.create_default_context(cafile=args.tls_cert)
            connection = stack.enter_context(tls.wrap_socket(connection, server_hostname=args.endpoint))
        connection.settimeout(None)

        def read_lines() -> None:
            try:
                with connection.makefile(encoding="utf-8", errors="replace") as incoming:
                    for line in incoming:
                        print(line.rstrip("\r\n"), flush=True)
            except OSError:
                pass

        def send(line: str) -> None:
            connection.sendall((line + "\r\n").encode())

        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        for line in (
            "CAP LS 302",
            f"CAP REQ :batch draft/metadata-{args.profile} labeled-response message-tags",
            f"NICK {CONTROL_NICK}",
            "USER fixture 0 * :Metadata fixture peer",
            "CAP END",
            f"METADATA * SUB {' '.join(PROFILE_KEYS)}",
            f"JOIN {args.channel}",
        ):
            send(line)
        try:
            for line in sys.stdin:
                if line.strip():
                    send(line.rstrip("\r\n"))
        except KeyboardInterrupt:
            pass
        finally:
            with contextlib.suppress(OSError):
                send("QUIT :Fixture observer finished")
                connection.shutdown(socket.SHUT_WR)
            reader.join(timeout=1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6670)
    parser.add_argument("--log", type=Path, default=Path("/tmp/motd-avatar-metadata.log"))
    parser.add_argument("--profile", type=int, choices=(2, 3), help="enable two-client profile mode")
    parser.add_argument("--operator", default="profilephone", help="nick which joins with +o")
    parser.add_argument("--peer", action="store_true", help="connect an interactive second client")
    parser.add_argument("--channel", default="#profiles", help="initial channel for --peer")
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--endpoint", default="profile.test", help="certificate and SOCKS destination hostname")
    parser.add_argument("--socks-port", type=int, help="serve the pinned IRC and HTTPS avatar routes over SOCKS5")
    parser.add_argument(
        "--avatar-url",
        default="https://avatars.githubusercontent.com/u/9919?s={size}",
    )
    args = parser.parse_args()
    if args.peer:
        if args.profile is None or not IRC_NAME.fullmatch(args.channel) or not args.channel.startswith("#"):
            parser.error("--peer requires --profile and a valid #channel")
        peer_console(args)
        return
    if args.profile is None and (args.tls_cert or args.tls_key or args.socks_port):
        parser.error("TLS and SOCKS require --profile")
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("--tls-cert and --tls-key must be supplied together")
    if args.socks_port is not None and (not args.tls_cert or args.port == 443):
        parser.error("--socks-port requires TLS and an IRC port other than 443")
    if args.profile is not None and args.host != "127.0.0.1":
        parser.error("profile mode listens only on IPv4 loopback")
    tls = None
    if args.tls_cert:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(args.tls_cert, args.tls_key)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text("", encoding="utf-8")
    state = ProfileState(args, tls) if args.profile else State(args.log, args.avatar_url)
    handler = ProfileIrcHandler if args.profile else AvatarIrcHandler
    with contextlib.ExitStack() as stack:
        server = stack.enter_context(ThreadingIrcServer((args.host, args.port), handler))
        server.state = state
        if args.socks_port is not None:
            proxy = stack.enter_context(ThreadingIrcServer((args.host, args.socks_port), FixtureSocksHandler))
            proxy.irc = server
            threading.Thread(target=proxy.serve_forever, daemon=True).start()
            stack.callback(proxy.shutdown)
            state.log("I", f"SOCKS listening {args.host}:{proxy.server_address[1]}")
        state.log("I", f"listening {args.host}:{server.server_address[1]}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

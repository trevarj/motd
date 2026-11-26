#!/usr/bin/env python3
"""Disposable loopback IRC peer for ChatInteractionBenchmark (Python stdlib only)."""

import argparse
from bisect import bisect_left, bisect_right
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from itertools import count
import socket
from socketserver import StreamRequestHandler, ThreadingTCPServer
from time import sleep
from typing import NamedTuple


HOST = "127.0.0.1"
SERVER = "motd-bench.local"
A, B = "#bench-a", "#bench-b"
CAPS = "message-tags server-time batch labeled-response echo-message standard-replies draft/chathistory"
ROSTER = ("@benchalice", "+benchbob")
EXTRA_MEMBERS = tuple(f"benchmember{i:03}" for i in range(1, 301))
NAMES_PER_LINE = 20
HISTORY_LIMIT = 50
SESSION_IDS = count(1)
MILLISECOND = timedelta(milliseconds=1)


class Row(NamedTuple):
    msgid: str
    time: datetime
    sender: str
    text: str
    reply_to: str | None = None


def show_time(value):
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def read_time(bound):
    if not bound.startswith("timestamp="):
        raise ValueError("expected timestamp selector")
    return datetime.fromisoformat(bound.removeprefix("timestamp=").replace("Z", "+00:00")).astimezone(timezone.utc)


def seed_history():
    start = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=1)
    rows = []
    for i in range(1, 39):
        if i == 26:
            rows.append(("bench-a-parent", "benchalice", "embedded parent body", None))
        else:
            text = f"Seed row {i:02}: a steady stream of plain chat for exercising the timeline."
            if i > 26:
                text += " This line also wraps on a phone so the reply target starts off screen."
            rows.append((f"bench-a-seed-{i:02}", "benchalice" if i % 2 else "benchbob", text, None))
    rows += [
        ("bench-a-reply", "benchbob", "embedded reply body", "bench-a-parent"),
        ("bench-a-ready", "benchalice", "chat-a ready", None),
    ]
    return {
        A: tuple(Row(msgid, start + timedelta(seconds=i), sender, text, reply)
                 for i, (msgid, sender, text, reply) in enumerate(rows)),
        B: (Row("bench-b-ready", start + timedelta(seconds=len(rows)), "benchbob", "chat-b ready"),),
    }


def tagged(line, tags):
    return "@" + ";".join(f"{key}={value}" for key, value in tags.items()) + " " + line if tags else line


def parse(line):
    tags = {}
    if line.startswith("@"):
        raw_tags, _, line = line.partition(" ")
        for entry in raw_tags[1:].split(";"):
            key, _, value = entry.partition("=")
            tags[key] = value  # Forward relevant client tag values as-is, including IRCv3 escapes.
    words, delimiter, trailing = line.partition(" :")
    args = words.split()
    if delimiter:
        args.append(trailing)
    return (args[0].upper(), args[1:], tags) if args else ("", [], tags)


def select_rows(rows, command, bounds, limit):
    def edge(bound):
        if bound.startswith("msgid="):
            msgid = bound.removeprefix("msgid=")
            for i, row in enumerate(rows):
                if row.msgid == msgid:
                    return i, i + 1
            raise ValueError("unknown msgid selector")
        stamp = read_time(bound)
        return bisect_left(rows, stamp, key=lambda row: row.time), bisect_right(rows, stamp, key=lambda row: row.time)

    bound = bounds[0]
    if command == "LATEST" and bound == "*":
        return rows[-limit:]
    lo, hi = edge(bound)
    if command == "LATEST":
        return rows[max(hi, len(rows) - limit):]
    if command == "BEFORE":
        return rows[max(0, lo - limit):lo]
    if command == "AFTER":
        return rows[hi:hi + limit]
    if command == "AROUND":
        start = max(0, min(lo - limit // 2, len(rows) - limit))
        return rows[start:start + limit]
    if command == "BETWEEN" and len(bounds) == 2:
        other_lo, other_hi = edge(bounds[1])
        if lo <= other_lo:
            return rows[hi:min(other_lo, hi + limit)]
        return rows[max(other_hi, lo - limit):lo]
    raise ValueError("unknown history command")


class Fixture(StreamRequestHandler):
    def handle(self):
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.nick = "*"
        self.have_user = self.negotiating = self.registered = False
        self.caps = set()
        self.joined = set()
        self.attempts = {}
        self.history = {target: list(rows) for target, rows in self.server.history.items()}
        self.session = next(SESSION_IDS)
        self.live_ids = count(1)
        self.batch_ids = count(1)
        try:
            while raw := self.rfile.readline(8193):
                if len(raw) > 8192:
                    return
                command, args, tags = parse(raw.decode("utf-8", errors="replace").rstrip("\r\n"))
                if command == "QUIT":
                    return
                self.dispatch(command, args, tags)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send(self, *lines):
        self.wfile.write(("\r\n".join(lines) + "\r\n").encode("utf-8"))

    def numeric(self, code, body):
        return f":{SERVER} {code} {self.nick} {body}"

    def welcome(self):
        if not self.registered and self.nick != "*" and self.have_user and not self.negotiating:
            self.registered = True
            self.send(
                self.numeric("001", ":Welcome to the MOTD benchmark fixture"),
                self.numeric("005", "CHANTYPES=# CASEMAPPING=rfc1459 PREFIX=(ov)@+ CHATHISTORY=50 "
                             "MSGREFTYPES=msgid,timestamp :are supported by this server"),
                self.numeric("422", ":No MOTD"),
            )

    def roster(self, target):
        end = self.numeric("366", f"{target} :End of NAMES list")
        if target not in self.joined:
            return (end,)
        names = (self.nick, *ROSTER)
        if target == A:
            names += EXTRA_MEMBERS
        lines = [
            self.numeric("353", f"= {target} :" + " ".join(names[i:i + NAMES_PER_LINE]))
            for i in range(0, len(names), NAMES_PER_LINE)
        ]
        return (*lines, end)

    def message(self, target, row, extra=None):
        tags = dict(extra or {})
        if "message-tags" in self.caps:
            tags["msgid"] = row.msgid
            if row.reply_to:
                tags["+draft/reply"] = row.reply_to
        if "server-time" in self.caps:
            tags["time"] = show_time(row.time)
        return tagged(f":{row.sender}!bench@localhost PRIVMSG {target} :{row.text}", tags)

    def append(self, target, sender, text, msgid=None, reply_to=None):
        rows = self.history[target]
        stamp = max(datetime.now(timezone.utc), rows[-1].time + MILLISECOND)
        row = Row(msgid or f"bench-live-{self.session}-{next(self.live_ids)}", stamp, sender, text, reply_to)
        rows.append(row)
        return row

    def chathistory(self, args, label):
        command, target = (args + ["*", "*"])[:2]
        command = command.upper()
        target = target.lower()
        batch = f"bench-history-{next(self.batch_ids)}"
        try:
            limit = min(HISTORY_LIMIT, int(args[-1]))
            if limit < 1:
                raise ValueError("invalid limit")
            if command == "TARGETS" and len(args) == 4:
                low, high = sorted(map(read_time, args[1:3]))
                found = sorted(
                    (self.history[room][-1].time, room)
                    for room in self.joined
                    if low < self.history[room][-1].time < high
                )
                opening = f":{SERVER} BATCH +{batch} draft/chathistory-targets"
                body = [f"@batch={batch} :{SERVER} CHATHISTORY TARGETS {room} {show_time(stamp)}"
                        for stamp, room in found[-limit:]]
            elif target not in self.joined:
                self.send(tagged(f":{SERVER} FAIL CHATHISTORY INVALID_TARGET {target} :No retained history", label))
                return
            elif len(args) in (4, 5):
                rows = select_rows(self.history[target], command, args[2:-1], limit)
                opening = f":{SERVER} BATCH +{batch} chathistory {target}"
                body = [self.message(target, row, {"batch": batch}) for row in rows]
            else:
                raise ValueError("invalid arguments")
        except (IndexError, ValueError):
            self.send(tagged(f":{SERVER} FAIL CHATHISTORY INVALID_PARAMS :Invalid history request", label))
            return
        self.send(tagged(opening, label), *body, f":{SERVER} BATCH -{batch}")

    def privmsg(self, target, text, tags, label):
        if target not in self.joined:
            self.send(tagged(f":{SERVER} FAIL PRIVMSG CANNOTSENDTOCHAN {target} :Not joined", label))
            return
        msgid = None
        if target == A and text.startswith("0 bench fail "):
            attempt = self.attempts.get(text, 0) + 1
            self.attempts[text] = attempt
            if attempt == 1:
                self.send(tagged(f":{SERVER} FAIL PRIVMSG CANNOTSENDTOCHAN {target} :Fixture rejects first attempt", label))
                return
            if attempt == 2:
                # ponytail: one short per-client pause, not a general delivery scheduler.
                sleep(1.2)
                msgid = "bench-retry-" + sha256(text.encode("utf-8")).hexdigest()[:16]
        row = self.append(target, self.nick, text, msgid, tags.get("+reply") or tags.get("+draft/reply"))
        if "echo-message" in self.caps:
            self.send(self.message(target, row, label))
        if target == A and text.startswith("bench burst"):
            rows = [
                self.append(target, "benchalice" if i % 2 else "benchbob", f"Incoming burst row {i:02}")
                for i in range(1, 6)
            ]
            rows.append(self.append(target, "benchbob", "incoming burst ready"))
            self.send(*(self.message(target, row) for row in rows))

    def dispatch(self, command, args, tags):
        label = {"label": tags["label"]} if "labeled-response" in self.caps and "label" in tags else {}
        if command == "CAP" and args:
            subcommand = args[0].upper()
            if subcommand == "LS":
                self.negotiating = not self.registered
                self.send(f":{SERVER} CAP {self.nick} LS :{CAPS}")
            elif subcommand == "REQ" and len(args) > 1:
                requested = args[1].split()
                ack = all(cap.lstrip("-") in CAPS.split() for cap in requested)
                if ack:
                    for cap in requested:
                        if cap.startswith("-"):
                            self.caps.discard(cap[1:])
                        else:
                            self.caps.add(cap)
                self.send(f":{SERVER} CAP {self.nick} {'ACK' if ack else 'NAK'} :{args[1]}")
            elif subcommand == "END":
                self.negotiating = False
            self.welcome()
        elif command == "NICK" and args and not self.registered:
            self.nick = args[0]
            self.welcome()
        elif command == "USER":
            self.have_user = True
            self.welcome()
        elif command == "PING" and args:
            self.send(f":{SERVER} PONG {SERVER} :{args[-1]}")
        elif not self.registered or not args:
            return
        elif command == "JOIN":
            for target in args[0].lower().split(","):
                if target not in self.history:
                    self.send(tagged(self.numeric("403", f"{target} :No such channel"), label))
                elif target not in self.joined:
                    self.joined.add(target)
                    self.send(tagged(f":{self.nick}!bench@localhost JOIN {target}", label), *self.roster(target))
                    if "draft/chathistory" not in self.caps:
                        self.send(*(self.message(target, row) for row in self.history[target]))
        elif command == "NAMES":
            self.send(*self.roster(args[0].lower()))
        elif command == "PART" and args[0].lower() in self.joined:
            target = args[0].lower()
            self.joined.remove(target)
            self.send(tagged(f":{self.nick}!bench@localhost PART {target}", label))
        elif command == "CHATHISTORY" and "draft/chathistory" in self.caps:
            self.chathistory(args, label)
        elif command == "PRIVMSG" and len(args) == 2:
            self.privmsg(args[0].lower(), args[1], tags, label)


class Server(ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=17668, help="IPv4 loopback TCP port (default: %(default)s)")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    with Server((HOST, args.port), Fixture) as server:
        server.history = seed_history()
        print(f"IRC fixture ready on {HOST}:{args.port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

# Architecture

Two Gradle modules: `:app` (Android) and `:irc` (pure JVM, zero Android
dependencies).

```mermaid
flowchart TD
    subgraph app[":app (Android)"]
        ui["ui/* (Compose, MVVM)"]
        repo["data/repo/*"]
        db["data/db (Room + FTS)"]
        proc["data/sync/EventProcessor"]
        push["push/* (UnifiedPush, RFC 8291)"]
        cm["service/ConnectionManager"]
    end
    subgraph irc[":irc (pure JVM)"]
        client["client/IrcClient (CAP/SASL, labels, batches)"]
        ircproto["proto/ (parser/serializer)"]
        transport["transport/ (okio + TLS)"]
    end

    ui -->|actions| cm
    ui -->|Flow / PagingData| repo
    repo --> db
    proc -->|sole Room writer| db
    cm -->|per-network| client
    client -->|IrcEvent| proc
    push --> proc
    client --> ircproto
    client --> transport
```

Key invariants:

- `EventProcessor` is the only component that writes IRC-derived state to Room.
- The UI reads only from repositories and sends actions only through
  `ConnectionManager`.
- TLS policy and client certificates are injected into `:irc` via
  `TransportFactory`, keeping the protocol module free of Android APIs.

Design documents live in [`plans/`](plans/).

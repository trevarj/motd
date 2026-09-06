# Architecture

motd has two Gradle modules: `:app` is the Android application and `:irc` is a
pure-JVM IRC engine with no Android dependencies.

```mermaid
flowchart TD
    subgraph app[":app (Android)"]
        ui["Compose UI + ViewModels"]
        repo["repositories / preferences"]
        db["Room + FTS"]
        proc["EventProcessor"]
        push["push delivery"]
        upload["previews / uploads"]
        cm["ConnectionManager"]
        androidTransport["Android transport integration"]
    end
    subgraph irc[":irc (pure JVM)"]
        client["IrcClient + extensions"]
        proto["parser / serializer"]
        socket["okio + Socket / SSLSocket"]
    end

    ui -->|state| repo
    ui -->|connection and IRC actions| cm
    repo --> db
    client -->|IrcEvent| proc
    proc -->|IRC-derived writes| db
    push --> proc
    cm --> androidTransport
    androidTransport --> client
    client --> proto
    client --> socket
    ui --> upload
```

## Key invariants

- `EventProcessor` is the only component that writes IRC-derived state to Room.
  Feature-local persistence, such as preferences and upload history, remains
  behind its own repository or preference contract.
- UI observes repositories and ViewModel state. Connection and protocol actions
  go through `ConnectionManager` instead of constructing IRC clients in screens.
- TLS policy, Android KeyChain integration, proxy selection, and embedded
  obfuscation are injected at the `:app` boundary so `:irc` stays pure JVM.
- IRC TCP/TLS uses okio over `Socket`/`SSLSocket`. App-side WebSocket transport
  uses the pinned OkHttp dependency. HTTP previews and attachment uploads use
  their existing `HttpURLConnection`-based streaming implementations.
- The app ships as a single Google-free build with no product flavors; push
  delivery is UnifiedPush only. The E2E build is x86_64-compatible and
  intentionally omits the arm64-only libbox JNI.
- Channel watch follows one canonical channel across redirects until its saved
  deadline (or until stopped). It admits live PRIVMSG/ACTION notifications and
  overrides mute, including already-qualifying push mentions; it does not request
  additional push deliveries or notify for history/replay. Self, ignore, fool,
  foreground, and read suppression remain. Original watch eligibility is stored
  with each event so interrupted notification recovery stays silent and does not
  reinterpret old messages using a later watch.

## Where to work

- `app/src/main/.../ui/` — Compose screens, components, navigation, and
  ViewModels.
- `app/src/main/.../data/` — Room, repositories, sync, preferences, and feature
  persistence.
- `app/src/main/.../service/` — connection ownership and Android lifecycle.
- `irc/src/main/` — protocol, client state machine, extensions, and transport.

Repository policy and task workflows live in [`AGENTS.md`](AGENTS.md) and
[`.agents/`](.agents/README.md).

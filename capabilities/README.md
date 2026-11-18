# MOTD protocol extensions

Work-in-progress specifications for MOTD-defined IRC extensions live here. They
follow IRCv3 terminology, wire limits, capability negotiation, vendor naming,
and fallback rules. Published identifiers use the controlled
`trevarj.github.io/` namespace; `draft/` is reserved for proposals under IRCv3
Working Group review.

A feature does not automatically need a new `CAP` capability. Client-only
message tags use `message-tags` plus `CLIENTTAGDENY`; server-advertised HTTP
services generally use `RPL_ISUPPORT`, as `soju.im/FILEHOST` does.

| Specification | Wire identifier | Status |
| --- | --- | --- |
| [Audio messages](audio.md) | Optional `+trevarj.github.io/audio` tag | Work in progress ([#75](https://github.com/trevarj/motd/issues/75)) |

## Recommended next specifications

1. **Call signaling.** Issue
   [#39](https://github.com/trevarj/motd/issues/39) already proposes
   `+trevarj.github.io/call`. Draft this only when implementation starts, after
   crypto and replay rules are settled.
2. **Generic attachment metadata.** Consider a vendor client tag only after a
   second media type needs metadata that cannot live in a readable URL
   fallback. Until then, audio's small profile is enough.
3. **Inline actions.** Closed issue
   [#27](https://github.com/trevarj/motd/issues/27) identified a vendor client
   tag as the least-bad carrier. Keep deferred until a real bot integration
   needs it.

Do not duplicate existing contracts here: `soju.im/FILEHOST`, IRCv3 replies,
reactions, typing, read markers, metadata, and DCC remain owned by their
upstream specifications.

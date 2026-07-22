# Required headless coverage map

The required API34 gate discovers exactly the three methods in
`RequiredHeadlessE2eTest`. It is intentionally narrow; the exhaustive host UIAutomator runbook
remains scheduled/manual.

| Removed broad assertion surface | Destination |
| --- | --- |
| onboarding, TLS trust, soju import | `onboardingTrustsEphemeralTlsAndImportsNetwork` plus onboarding reducer tests |
| retained-history backfill and mention/non-mention presentation | onboarding journey’s imported ready child/chat-list proof; deterministic CHATHISTORY/EventProcessor and mention-presentation tests |
| 18-send layout and coordinate anchor | layout preferences/component and keyed-anchor unit tests; long-list pixels remain scheduled UIAutomator |
| join, send, search, completion, command, `/me` | one canonical UI send in `sendEchoPersistsVisibleRowAndReconnects`; composer/parser/ViewModel/EventProcessor tests |
| replies, reactions, drafts | deterministic delivery, preview, mutation, canonical, and draft tests; choreography remains manual |
| reconnect duplicate/new second send | one canonical row before/after reconnect plus deterministic echo/canonical/resync tests |
| channel/member/friend/fool sheets | ViewModel/presentation/repository tests; scheduled UIAutomator |
| settings and bouncer panels | `bootstrappedNavigationSettingsAndBouncerSmoke` plus preferences/catalog/model/ViewModel tests |
| verified BouncerServ account/channel/console capability discovery | navigation/bouncer smoke after root readiness; BouncerServ command/session/model tests retain protocol and authorization detail |
| host reconnect phase R / 40-row gap | EventProcessor/resync/canonical deterministic tests and manual ZNC/runbook gap validation |

No assertion is removed without one of these destinations. The required suite never adds a second
real-stack send, and host UIAutomator cannot acquire `@FastHeadlessE2e`.

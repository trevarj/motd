# 05 — Foreground service & connection lifecycle (WP5)

## Manifest (WP1 authors; exact)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".service.IrcForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Maintains persistent IRC connections for real-time chat delivery" />
</service>
<receiver android:name=".service.BootReceiver" android:exported="false">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
</receiver>
<receiver android:name=".service.ReplyReceiver" android:exported="false" />
```

`specialUse`, NOT `dataSync` — Android 15 caps dataSync at 6h/day, fatal for a persistent
socket. Play policy review is irrelevant (GitHub-distributed).

## IrcForegroundService

- Thin `LifecycleService` shell: `onStartCommand` → `startForeground(1, statusNotification)`
  → `connectionManager.startAll()`; `onDestroy` → `stopAll()`. `START_STICKY`.
- Status notification (channel `status`, IMPORTANCE_MIN, silent): "Connected to N networks" /
  "Reconnecting…", content intent → MainActivity, stop action.
- Started from: MainActivity when `deliveryMode == PERSISTENT_SOCKET` and networks exist
  (`startForegroundService`); BootReceiver (same condition; API 34+ allows FGS start from
  BOOT_COMPLETED for specialUse — if `ForegroundServiceStartNotAllowedException` is thrown,
  swallow it; the app connects on next open).
- Settings offers `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` deep link ("keep alive in
  Doze") — optional, off by default.

## ConnectionManagerImpl (Hilt @Singleton — outlives the service; service is just its keeper)

- Own scope: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- `startAll()`: for each `networkDao.connectable()` row spawn a `ConnectionActor`. Rows with
  role BOUNCER_ROOT get one actor (root connection, used for bouncer-networks-notify + WEBPUSH
  + LISTNETWORKS); each BOUNCER_CHILD gets its own actor whose `IrcClientConfig` copies the
  root's host/SASL and sets `bouncerNetId`. DIRECT rows are one actor each.
- Observes `networkDao.observeAll()` → reconcile actors (add/remove/restart on config change);
  observes `settings.deliveryMode` → PERSISTENT_SOCKET: ensure service running;
  UNIFIED_PUSH: after webpush registration is confirmed (plans/06), `stopAll()` + stopService.

### ConnectionActor (one per physical socket)

```
loop:
  state = Connecting → build IrcClient(config, appTransportFactory, actorScope) → start()
  on Ready:
     backoffAttempt = 0 after 5 min stable (timer)
     launch EventProcessor collector for client.events
     run catch-up (plans/04) then steady state
  on Disconnected/transport failure:
     cancel collector; if Failed(fatal) → stop actor, surface state, do NOT retry
     else delay = min(90s, 2s * 2^attempt) * jitter(0.7..1.3); wait; retry
  network callbacks: ConnectivityManager.NetworkCallback
     onAvailable → skip remaining backoff delay (retry now)
     onLost → fast-fail current connect attempt
```

- `appTransportFactory`: wraps `OkioLineTransport`, injecting an `SSLContext` with the
  network's client cert (`clientCertAlias`, Android KeyChain) when SASL EXTERNAL; enforces
  stored STS policies (plans/03) by rewriting (port, tls) before connect and persisting new
  policies from `Ready.caps`.
- `sendMessage(bufferId, text, replyToMsgid)`: resolve buffer→network/target; split >400-byte
  UTF-8 texts on word boundaries into multiple PRIVMSGs; delegate the pending-row insert to
  EventProcessor (plans/04 echo flow). `/`-commands are parsed in the ViewModel (plans/07) —
  ConnectionManager handles exactly ONE command translation: text with leading `"/me "` is
  sent as a CTCP ACTION. Everything else goes out verbatim as PRIVMSG.
- Message notifications (channel `messages`, IMPORTANCE_HIGH; channel `mentions` separate):
  posted by EventProcessor hook when a ChatMessage arrives with (DM || hasMention) and buffer
  not muted and app not foregrounded on that buffer (read the `ForegroundBufferTracker`
  contract interface; ChatViewModel sets it). `MessagingStyle` per buffer, `notificationId = bufferId`,
  direct-reply `RemoteInput` → ReplyReceiver → `connectionManager.sendMessage`, mark-read
  action → `markRead`.

## WP5 tests (plain JVM/Robolectric, FakeTransport-scripted)

- EventProcessor: scripted IrcEvent stream → exact Room rows (join/quit fan-out, mention flag,
  query buffer auto-create).
- Echo dedup end-to-end: send → pending row → echo updates in place → identical
  msgid via HistoryBatch ignored → exactly one row.
- Catch-up: newestTime drives AFTER pagination loop; TARGETS creates missing buffers.
- Backoff: virtual-time test of delay sequence + reset-after-stable + fatal-no-retry.

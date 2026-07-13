# Firebase Cloud Messaging push

MOTD currently publishes only the `foss` APK, with persistent sockets and
UnifiedPush. The Google/FCM flavor below is unfinished and paused: it is not a
CI APK build or release artifact, and agents should not build or publish it
unless the maintainer explicitly reactivates the integration.

FCM cannot accept a Web Push endpoint directly. The included Firebase Function gives soju an
opaque endpoint, forwards the encrypted RFC8291 body as an FCM data message, and leaves decryption
to the app. Firebase credentials never belong in the APK.

## Create and deploy the relay

1. Create a Firebase project, enable Cloud Messaging, create a Firestore database, and register
   Android app `io.github.trevarj.motd` in the [Firebase console](https://console.firebase.google.com/).
2. Copy `.firebaserc.example` to `.firebaserc` and set the project ID.
3. Install and test the pinned Functions dependencies:

   ```sh
   nix develop -c npm ci --prefix firebase/functions
   nix develop -c npm test --prefix firebase/functions
   nix develop -c npx --yes firebase-tools@15.23.0 login
   ```

4. Set the public URL in `firebase/functions/.env.<project-id>`:

   ```text
   PUBLIC_BASE_URL=https://us-central1-PROJECT_ID.cloudfunctions.net/relay
   ```

5. Deploy with `nix develop -c npx --yes firebase-tools@15.23.0 deploy --only functions,firestore`.

Firestore rules deny all client access. The function uses credentials supplied by Firebase.
Subscription IDs and management secrets are random; registration and delivery are rate-limited,
and Firestore TTL removes stale subscriptions.

## Future reactivation inputs

When the integration is reactivated, these identifiers will be required from
Firebase project settings:

```sh
export MOTD_FIREBASE_API_KEY='...'
export MOTD_FIREBASE_APP_ID='1:...:android:...'
export MOTD_FIREBASE_PROJECT_ID='...'
export MOTD_FIREBASE_SENDER_ID='...'
export MOTD_FCM_RELAY_URL='https://us-central1-PROJECT_ID.cloudfunctions.net/relay'
```

The API key and application identifiers are client configuration, not
service-account secrets. No Google APK is currently built by CI or the release
workflow. Reactivation must first define its test gates, Firebase secrets, relay
deployment, and release support as one explicit work package.

## Use it

Connect to soju once, then select **Settings → Message delivery → Firebase Cloud Messaging
(Google)**. MOTD creates one relay subscription per auto-connect network and closes sockets only
after every eligible network has a registered endpoint.

References: [Firebase Android setup](https://firebase.google.com/docs/android/setup),
[receiving FCM messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
and [trusted sending environments](https://firebase.google.com/docs/cloud-messaging/server-environment).

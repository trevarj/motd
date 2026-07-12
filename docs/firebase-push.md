# Firebase Cloud Messaging push

MOTD publishes two APKs. The `foss` APK supports persistent sockets and UnifiedPush. The
`google` APK also supports Firebase Cloud Messaging (FCM). Both push paths require soju's
`soju.im/webpush` capability.

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

## Build the Google APK

Get these identifiers from Firebase project settings and export them for the build:

```sh
export MOTD_FIREBASE_API_KEY='...'
export MOTD_FIREBASE_APP_ID='1:...:android:...'
export MOTD_FIREBASE_PROJECT_ID='...'
export MOTD_FIREBASE_SENDER_ID='...'
export MOTD_FCM_RELAY_URL='https://us-central1-PROJECT_ID.cloudfunctions.net/relay'
nix develop -c ./gradlew :app:assembleGoogleDebug
```

The API key and application identifiers are client configuration, not service-account secrets.
The APK lands under `app/build/outputs/apk/google/debug/`. A Google build with incomplete values
compiles and reports FCM unavailable. Release CI currently treats the client values as optional,
while still building and testing the Firebase relay itself.

To enable FCM in GitHub release APKs, create repository secrets `FIREBASE_API_KEY`, `FIREBASE_APP_ID`,
`FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID`, and `FCM_RELAY_URL` with the corresponding values.

## Use it

Connect to soju once, then select **Settings → Message delivery → Firebase Cloud Messaging
(Google)**. MOTD creates one relay subscription per auto-connect network and closes sockets only
after every eligible network has a registered endpoint.

References: [Firebase Android setup](https://firebase.google.com/docs/android/setup),
[receiving FCM messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages),
and [trusted sending environments](https://firebase.google.com/docs/cloud-messaging/server-environment).

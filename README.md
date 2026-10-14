# OpenBased for Android

The Android app for [OpenBased](https://github.com/Stoppedwumm/openbased), the self-hosted media server. It
browses your libraries, streams to the phone, and downloads movies and episodes to watch offline.

- **Sign in** with the server's own login page in a Custom Tab (Authorization Code + PKCE, the built-in
  `openbased-android` client). The app never sees your password. It keeps the sign-in with rotating refresh
  tokens, stored encrypted.
- **Browse and search offline**: the catalogue is kept in a local database and synced incrementally
  (`/media/removed` + `/media?updatedSince=`), falling back to a full reload when the server asks for one.
- **Streaming** with Media3/ExoPlayer. The app tells the server which codecs the phone decodes, and the server
  picks direct play, remux or transcode. Remuxed and transcoded streams can still be seeked: the app restarts
  them at the new position (`?start=`). Subtitles (embedded text tracks and `.srt`/`.vtt` sidecars) are loaded
  as WebVTT.
- **Downloads**: the original file, or an optimized copy (1080p, 720p or 480p) that the server transcodes
  for this phone. Downloads run in the background, survive restarts and resume where they stopped (`If-Range`,
  so a file that changed on the server is fetched again rather than spliced). Subtitles and the poster are
  saved too. "Wi-Fi only" is on by default. Downloads can be saved to internal storage or an SD (TF)
  card (Settings → Save downloads to); no storage permission is needed. A download on a card that is not
  inserted shows as not available and the item streams instead.
- **Picture-in-picture**: leaving the player while it plays shrinks it into a floating window with rewind,
  play/pause and forward buttons (automatically on Android 12+). A media session gives headset, Bluetooth and
  system media controls.
- **Dual-screen devices**: on Android handhelds with two screens (such as the AYN Thor or AYANEO Flip DS), or
  with an external display, the video plays on the larger screen and the other one shows touch controls: seek
  bar, play/pause, subtitle and audio track choice, **Swap screens** and **Close**. The second screen is driven
  by a companion activity opened there; on displays apps cannot open on, the video is shown there in a
  video-only window instead. Settings lists the screens Android reports.
- **Leaving the player**: the close button (top left, with the controls), Back, B or Escape end playback, on
  either screen.
- **Game controllers**: A plays and pauses, L1/R1 skip back 10 s and forward 30 s, the D-pad seeks while the
  controls are hidden, Select swaps screens, Y shows the controls, B closes the player.
- **Watch progress** is saved locally first and sent in batches with the time it was recorded, so watching
  offline or on a flaky connection is never lost and the newest progress across devices wins.

## Server requirements

Everything the app uses is in OpenBased `claude/serene-hawking-8lbcn6` as of `c48d712` (Stoppedwumm/openbased#1
to #6 are merged). Sign-in needs at least `de83085` (built-in Android client, refresh tokens for public
clients). On older servers the app degrades instead of failing:

| Feature | Server change | Without it |
| --- | --- | --- |
| Offline progress | Stoppedwumm/openbased#1 | — (required) |
| Original downloads | Stoppedwumm/openbased#2 | — (required for downloads) |
| Optimized downloads | Stoppedwumm/openbased#3 | The original file is downloaded |
| Incremental sync | Stoppedwumm/openbased#4 | Every sync reloads the catalogue |
| Artwork sizes (`?w=`) | Stoppedwumm/openbased#5 | Full-size artwork is loaded |
| Subtitles | Stoppedwumm/openbased#6 | Played without subtitles |

## Building

Requirements: JDK 17 or newer and the Android SDK (platform 35). Set `ANDROID_HOME` or create
`local.properties` with `sdk.dir=…`.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests (JVM + Robolectric)
./gradlew lintDebug
```

Install on a device with `adb install app/build/outputs/apk/debug/app-debug.apk`, then enter the address you
open OpenBased at in the browser (for example `http://bigbox:8080`). That address must be the server's
`openbased.issuer`, because sign-in only works there.

Plain HTTP and user-installed certificates are allowed, because home servers often use one or the other.

### Testing against a real server

`LiveServerTest` runs the app's networking code against a running server: it signs in through the login page
as the Custom Tab would, refreshes concurrently, syncs, opens a playback session, reads subtitles, sends
progress, and downloads (original with a resume, and an optimized copy). It is skipped unless you pass a server
and a user; the server needs at least one video:

```sh
./gradlew testDebugUnitTest --tests '*LiveServerTest' \
    -PliveServer=http://localhost:8080 -PliveUser=bob -PlivePassword=…
```

Behind a rate-limited Maven Central, `-ProbolectricRepoUrl=<mirror>` points Robolectric's own downloads at a
mirror.

## How it fits together

| Package | Contents |
| --- | --- |
| `auth` | `AuthManager` (discovery, PKCE sign-in, single-flight refresh, sign-out), `EncryptedTokenStore`, `RedirectActivity` |
| `api` | `OpenBasedApi` (REST client), `AuthInterceptor` (adds the token for the signed-in server only; refreshes once on 401), models |
| `data` | Room database (catalogue, downloads, progress queue), `CatalogRepository` (sync), `ProgressRepository` (queue + batch), sync workers |
| `download` | `DownloadRepository`, `DownloadWorker` (WorkManager, foreground), `ResumableFetcher` (Range + If-Range) |
| `playback` | `PlayerActivity` (incl. picture-in-picture, dual screens, controller keys), `CompanionActivity` (video or controls on the second screen), `VideoPresentation` (video-only fallback), `RestartingPlayer` (seeking in remuxed/transcoded streams), `PlayerModes`, `DeviceCapabilities` |
| `ui` | Jetpack Compose screens: sign-in, home, library, series, search, detail, downloads, settings |

### Refresh tokens

The server rotates refresh tokens and revokes the whole sign-in when an old one is used again. So the app
serializes refreshes: if several requests fail with the same expired token at once, only one refresh is sent
and the others reuse its result. The new refresh token is written to disk (`commit()`) before the new access
token is used. If a refresh response is lost in transit after the server rotated the token, the old token is
then invalid and the user has to sign in again; that is the price of reuse detection.

## Limitations

- The UI has been built and unit-tested, and the networking has been tested end-to-end against a real server,
  but the app has not yet been run on a device or emulator. Expect UI rough edges.
- Music libraries list their tracks, but there is no audio-specific player (no background playback or queue).
- No Chromecast or Android TV interface yet.
- Picture-in-picture and dual-screen mode are built and their logic is unit-tested, but have not been tried
  on a device by us. Playback pauses when the player screen goes to the background, also with two screens.
- Signing out deletes downloads and any progress that has not been sent yet (the dialog says so).
- Items with several files always play and download the first file.

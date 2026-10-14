**To:** OpenBased dev team
**Subject:** Proposal: an official OpenBased Android app with offline downloads, and the server changes it needs

Hi OpenBased team,

I'd like to build an Android app for OpenBased (repository: `Stoppedwumm/openbased-android`). It would be a
native client of the existing `/api/v1` API, like the web UI and the Kodi add-on. Users could browse
libraries, search, stream with direct play, remux or transcode, resume where they left off, and **download
media to watch offline**.

I've gone through the server code on the `claude/serene-hawking-8lbcn6` branch. Most of what the app needs
is already there: Authorization Code + PKCE, playback sessions with capability negotiation, Range
streaming of the original file, progress and continue-watching, events over WebSocket, and device linking.
Downloads and offline use are the exception: they need a few server changes. I've listed them below,
most important first, and I'm happy to write the PRs myself if you agree with the approach.

---

### 1. A built-in OAuth2 client for the Android app (required)

At the moment only `openbased-web` is configured by default, and its redirect URIs are relative to
`openbased.issuer`. A mobile app can't ask every self-hoster to edit `application.yml` before signing in.

- Add a default public client, e.g. `openbased-android`, with `grant-types: [authorization_code, refresh_token]`
  (PKCE is already required for public clients).
- Give it a fixed redirect URI using a private-use URI scheme (RFC 8252), e.g.
  `org.openbased.android:/oauth2redirect`. This doesn't depend on the server's hostname, so it works for every
  install.
- Optional: allow `require-consent` to be turned off for it, the same as the web client.

### 2. Long-lived sign-in: refresh tokens for the native client (required for downloads)

The README's "Known gaps" says that Spring Authorization Server doesn't issue refresh tokens to public
clients, so they have to re-run the authorization flow, and that authorizations are kept in memory.
Access tokens last 15 minutes. That works for the browser, but not for an app that:

- runs background downloads (Android WorkManager) that can take hours,
- comes back online after days offline and has to sync progress without opening a browser,
- shouldn't sign the user out every time the server restarts.

Requested changes:

- Issue **rotating refresh tokens to the native public client** (`reuse-refresh-tokens: false` is already set).
  This needs a custom authentication provider/token generator for `ClientAuthenticationMethod.NONE`, the
  approach Spring documents for native apps.
- **Persist OAuth2 authorizations** (`JdbcOAuth2AuthorizationService` instead of the in-memory service) so
  refresh tokens survive restarts.
- Alternative if you'd rather not do the above: let the app use the existing **device-link flow**
  (`POST /api/v1/device-links`) to get a personal access token. The scopes in `DeviceLinkService.DEVICE_SCOPES`
  would then need to include the download scope from point 3.

### 3. A download endpoint and a `media.download` scope (required)

`GET /api/v1/media/{id}/stream` already serves the original file with Range support, so technically the app
could download through it. For reliable, resumable downloads I'd like:

- **A new scope/permission `media.download`**, separate from `media.stream`, so admins can allow streaming
  without allowing downloads. It would be added to the `USER` role by default (or not, if you prefer), and to the
  device-link scopes.
- **`GET /api/v1/media/{id}/download?fileId=…`** (scope `media.download`), which returns the original file with:
  - `Content-Disposition: attachment; filename="…"`,
  - `Content-Length`, Range support, and **`ETag` / `Last-Modified` with `If-Range` support**. Without a validator, a
    resumed download can stitch together two versions of a file that changed on disk (re-encode, replacement).
- Optional: a checksum on `FileInfo` (e.g. `sha256`, computed lazily during the scan), so the app can verify a
  finished download. `FileInfo.size` is already there.

### 4. Server-side "optimized" (transcoded) downloads (strongly wanted)

Many originals are too large for a phone or use codecs the device can't decode. Today `REMUX`/`TRANSCODE`
playback is a live ffmpeg pipe (`pipe:1`, fragmented MP4) with no `Content-Length` and no Range, so it can't be
downloaded reliably or resumed. My proposal:

- `POST /api/v1/downloads` with `{ mediaId, fileId, capabilities, maxHeight, maxVideoBitrate }`. The server
  decides the same way `PlaybackPlan.decide` does. If direct play works, it points to the original. Otherwise it
  starts a **job** (the existing `JobService`) that transcodes to a file under `data-dir`.
- `GET /api/v1/downloads/{id}` reports status and progress. `GET /api/v1/downloads/{id}/file` serves the result
  with Range, `ETag` and `Content-Length`. `DELETE` cancels or cleans it up. Results expire after a configurable
  time (`openbased.downloads.retention`).
- Resolution and bitrate limits in `Transcoder` (`-vf scale`, `-maxrate`/`-bufsize`). Today it only chooses
  codecs.
- New events `DOWNLOAD_READY` / `DOWNLOAD_FAILED` (or general `JOB_*` events) on `WS /api/v1/events`, so the app
  doesn't have to poll.

### 5. Syncing progress that was recorded offline (required)

`PUT /api/v1/media/{id}/progress` stamps `updatedAt` and `watchedAt` with the server's `Instant.now()`. If you
watch something offline and sync it the next day, two things go wrong: the history shows the wrong time, and the
old position **overwrites newer progress** from another device.

- Add an optional client timestamp to `ProgressUpdate`, e.g. `"recordedAt": "2026-10-13T20:15:00Z"`, clamped to
  no later than the current server time. Only apply the update if it is newer than the stored `updatedAt`
  (last write wins), and use that time for the history entry.
- Optional: `POST /api/v1/progress/batch` to upload many queued updates in one request.

### 6. Smaller things that would help the app

- **`updatedSince` filter on `GET /api/v1/media`** (sorting by `updatedAt` already exists), so the app can refresh
  its offline catalogue incrementally. `MEDIA_REMOVED` events already let it delete stale downloads while
  connected. A way to catch up on removals afterwards would also help, e.g. a removed-IDs endpoint or tombstones.
- **Subtitles**: I didn't find any subtitle support in the API. For offline viewing, the app would need to list
  and download embedded or sidecar subtitle tracks (e.g. `GET /api/v1/media/{id}/subtitles/{trackId}` as WebVTT/SRT).
- **Artwork sizes**: a `?width=` parameter on `GET /api/v1/artwork/{id}`, to keep thumbnail downloads small on
  mobile data.
- **Seeking in transcoded streams**: the current `?start=` approach works with ExoPlayer, but HLS output for
  `TRANSCODE` would make seeking and adaptive quality much better on mobile. This is a later improvement,
  not a blocker.

---

### What doesn't need to change

- Streaming auth works as it is: ExoPlayer can send `Authorization` headers, so the app won't need
  `?access_token=` in URLs.
- Sign-out works with the existing endpoints: token revocation, plus `DELETE /api/v1/tokens/current` for PATs.
- Library isolation (404 for other users' media) and rate limiting need no changes.

### Proposed order

1. Android OAuth client + refresh tokens + persisted authorizations (points 1–2). This unblocks the app itself.
2. `media.download` + `/download` with ETag/If-Range, and offline progress timestamps (points 3 and 5). This
   gives a first version with offline downloads.
3. Optimized downloads via jobs (point 4), then the smaller items in point 6.

Could you let me know whether this direction works for you, especially the refresh-token approach and adding a
separate `media.download` permission? If so, I'll open an issue for each point and start with the PRs for points
1 and 2.

Thanks, and thanks for building OpenBased!

Best regards,
[Your name]
[GitHub username / contact]

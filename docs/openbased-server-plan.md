# OpenBased server: what was agreed for the Android app

Agreed with the OpenBased team by email (reply to `email-to-openbased-team.md`). Server PRs are based on
`Stoppedwumm/openbased@claude/serene-hawking-8lbcn6` until that branch is merged into `main`.

## Decisions that affect the app

| Topic | Decision |
| --- | --- |
| Sign-in | Authorization Code + PKCE in **Custom Tabs**, not device linking (that is for TVs). Client `openbased-android`, built into the server in code (on by default, can be turned off), redirect `org.openbased.android:/oauth2redirect`, no consent screen. |
| Refresh tokens | Rotating, with reuse detection: **presenting an already-used refresh token revokes the whole sign-in**. The app must store the new refresh token atomically and never retry a refresh with an old one. |
| Sign-out | `/oauth2/revoke` will accept public clients (fixed by the server team together with refresh tokens). |
| Downloads | Scope `media.download` (granted to `USER` by default; admins can remove it). It's a policy for honest clients, not protection. `GET /media/{id}/download` with `Content-Disposition`, `Content-Length`, Range, `ETag`, `Last-Modified`, `If-Range`. No sha256 for now: verify with `size` + `ETag`. |
| Optimized downloads | `POST /downloads` → job; `GET /downloads/{id}`, `GET /downloads/{id}/file` (Range, `ETag`), `DELETE`; `maxHeight`, `maxVideoBitrate`; `DOWNLOAD_READY`/`DOWNLOAD_FAILED` events; expire after `openbased.downloads.retention`. |
| Offline progress | `recordedAt` on `PUT /progress`, `POST /progress/batch`, newest update wins. **We implement this.** |
| Incremental sync | `updatedSince` on `/media` plus a list of IDs removed since then (design already agreed with another client). |
| Artwork | `?w=` (not `?width=`), resized and cached on the server. |
| Subtitles | Embedded (ffprobe) + sidecar `.srt`/`.vtt`, served as WebVTT from `/media/{id}/subtitles/{trackId}`. |
| HLS | Later. |

## Who does what, in order

1. Points 1 + 2 (client, refresh tokens, persisted sign-ins, revoke): **server team**. Landed in `de83085` on `claude/serene-hawking-8lbcn6`; the built-in client can be turned off with `openbased.builtin-clients.android`.
2. Point 5 (offline progress): **us**. **Merged** as Stoppedwumm/openbased#1 (`c49c5c5`), with follow-ups from the team:
   - `recordedAt` is also clamped to no earlier than the time the media item was added (protects against reset device clocks).
   - A history entry is only extended by a viewing at or before `recordedAt`; an offline viewing synced late becomes its own entry.
   - An unexpected error in one batch item gives `FAILED` / `INTERNAL_ERROR` for that item; a malformed item rejects the whole batch with `422 VALIDATION_FAILED`.
   - Concurrent first updates for the same media item retry once instead of returning 500 (`1b6d7ae`).
3. Point 3 (`media.download` + `/download`): **us**. **Merged** as Stoppedwumm/openbased#2 (`21e298e`), unchanged. For the app's downloader:
   - Check `media.download` in the granted scopes before offering downloads.
   - `HEAD` first to get `Content-Length` and `ETag`; resume with `Range: bytes=<n>-` + `If-Range: <ETag>`.
   - A `206` continues the file; a `200` means the file changed, so restart from zero with the new `ETag`.
4. Point 4 and point 6: **us**, in review (each PR is independent; all four together pass 52 tests and the Kodi tests):
   - Optimized downloads: Stoppedwumm/openbased#3 (`POST /downloads` → `DOWNLOAD_READY` event → `GET /downloads/{id}/file`).
   - Incremental sync: Stoppedwumm/openbased#4 (`GET /media/removed?since=` first; if `resetRequired`, reload; else `GET /media?updatedSince=`; store `syncedAt`).
   - Artwork sizes: Stoppedwumm/openbased#5 (`?w=` rounds up to 92/154/185/342/500/780/1280).
   - Subtitles: Stoppedwumm/openbased#6 (`GET /media/{id}/subtitles`, then `/subtitles/{trackId}` as WebVTT; works with `media.download` for offline).
   - HLS: later.

## Issue drafts (one per point, for `Stoppedwumm/openbased`)

### 1. Built-in `openbased-android` OAuth2 client
Register a public client `openbased-android` in code (not `application.yml`, which admins replace): Authorization
Code + PKCE + refresh tokens, redirect `org.openbased.android:/oauth2redirect`, no consent screen. Built-in
clients are on by default with a setting to turn each off. *Owner: OpenBased team.*

### 2. Refresh tokens for public clients, persisted authorizations, revoke for public clients
Rotating refresh tokens with reuse detection for `ClientAuthenticationMethod.NONE`; `JdbcOAuth2AuthorizationService`
so sign-ins survive restarts; `/oauth2/revoke` usable by public clients. *Owner: OpenBased team.*

### 3. `media.download` scope and `GET /api/v1/media/{id}/download`
New scope/permission (in `USER` by default; built-in role permissions become configurable). Endpoint returns the
original with `Content-Disposition`, `Content-Length`, Range, `ETag`, `Last-Modified` and honours `If-Range`.
Document that `media.stream` can already read the whole file. Checksums are out of scope. *Owner: Android team.*

### 4. Optimized downloads
`POST/GET/DELETE /api/v1/downloads…` backed by `JobService`, same decision as `PlaybackPlan.decide`, `maxHeight` /
`maxVideoBitrate` in `Transcoder`, `DOWNLOAD_READY` / `DOWNLOAD_FAILED` events, `openbased.downloads.retention`.

### 5. Offline progress sync
`recordedAt` on `PUT /api/v1/media/{id}/progress` (clamped to the server clock, older updates ignored, used for the
history entry) and `POST /api/v1/progress/batch`. *Owner: Android team; implementation ready.*

### 6. Incremental sync, artwork sizes, subtitles, HLS
`updatedSince` + removed IDs on `/media`; `?w=` on artwork; subtitle listing and WebVTT serving; HLS later.

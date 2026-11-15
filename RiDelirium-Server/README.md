# Voice token server

Group voice during a ride runs on [LiveKit](https://livekit.io). The apps never hold LiveKit secrets;
they ask this endpoint for a short-lived token for their group's room.

## Set up
1. Create a LiveKit project (cloud.livekit.io has a free tier) and note its **WebSocket URL**
   (`wss://<project>.livekit.cloud`), **API key** and **API secret**.
2. Put `token.php` on any PHP 7.4+ host over **HTTPS**. Copy `config.example.php` to `config.php`
   and fill in the key and secret (or set `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET`).
3. Check it:
   ```
   curl -s -X POST https://your-host/token.php \
     -d '{"room":"ABCD2345","identity":"test-1","name":"Test"}'
   ```
   You should get `{"token":"..."}`.
4. In the iOS app fill `voiceTokenURL` and `liveKitURL` in `Secrets.swift`; in the Android app add
   `VOICE_TOKEN_URL` and `LIVEKIT_URL` to `local.properties`.

## Good to know
- The room is `sb-<8-character Ride Together code>`. Anyone who knows the code can ask for a token, so
  treat codes like a shared password. Add rate limiting (or real accounts) before a wide release.
- Tokens last 6 hours.

# Ride Together API (`api/`)

Accountless friends list. A rider registers once with a random id and a display name, gets a secret
token back, and uses their invite code to become friends with whoever joins it. Endpoint list is at
the top of `api/index.php`.

## Set up
1. Create a MySQL 8 / MariaDB 10.5+ database and run `api/schema.sql`.
2. Add `DB_DSN`, `DB_USER`, `DB_PASS` and `RATE_SALT` to `config.php` (see `config.example.php`).
3. Serve `api/` over **HTTPS** with PHP 8.0+ (needs `pdo_mysql`) so `/v1/...` reaches `api/index.php`.
   Apache uses the included `.htaccess`; nginx: `try_files $uri /api/index.php$is_args$args;`.
4. Cron, daily: `php api/purge.php`.
5. Check: `curl -s -X POST https://your-host/v1/riders -d '{"id":"<uuid>","name":"Test"}'` returns a token.

## What is stored
Per rider: random id, sha256 of the token, display name, initials, what they ride (ski, board or bike), a "riding until" time and the last
day seen. Plus invite codes, friend pairs, and salted-hash rate-limit counters (deleted after a day).
No email, phone, device info, IP address, ride data or location history. While a rider is sharing with friends, their latest position (lat/lon) is kept, overwritten on every update, shown only to their friends and cleared shortly after the ride ends. Riders unseen for 30 days are
deleted, and `DELETE /v1/riders/me` removes everything about a rider immediately.

## Updating an existing database
Run each file in `api/migrations/` you have not run yet, in order. The newest, `004_sport.sql`, adds the `sport`
column. Changing it (`PUT /v1/riders/me {"sport"}`) sends a silent push to everyone in the rider's crew so their
open apps redraw the tag at once.

## Docker on the QNAP (Nginx Proxy Manager in front)

NAS layout: `/share/Container/ridersapi/` holds `Dockerfile`, `docker-compose.yml`, `.env` and `api/`.

```sh
cd /share/Container/ridersapi
cp .env.example .env && chmod 600 .env   # fill in DB_PASS and RATE_SALT
docker compose up -d --build
```

In NPM set the proxy host `ridersapi.sportco.net` to scheme `http`, host `ridersapi`, port `80`.
Cache assets and Websockets are not needed. Check with `curl https://ridersapi.sportco.net/v1/friends` (expect 401).
The app's API base URL becomes `https://ridersapi.sportco.net/v1`.

Debug 502: `docker logs ridersapi`, `docker exec nginx-proxy-manager curl -v http://ridersapi/v1/friends`.
Daily purge: QTS cron, `docker exec ridersapi php purge.php`.
MariaDB must accept TCP from the Docker bridge; the `rider` user needs host `%` (or `172.%`).

## Push notifications

Joining a code adds you to its owner's whole crew (friends with the owner and everyone they ride with), and everyone in it is notified. A rider who joins or leaves your crew sends you "Anna joined your crew" (APNs on iOS, FCM on Android).

1. Apple Developer > Certificates, Identifiers & Profiles > Keys > + > tick Apple Push Notifications service (APNs). Download `AuthKey_XXXXXXXXXX.p8` once.
2. Copy it to `/share/Container/ridersapi/apns.p8` (`chmod 600`). Put `APNS_KEY_ID` (the X's) and `APNS_TEAM_ID` in `.env`.
3. Run `./deploy.sh`.
4. The key works for debug (sandbox) and TestFlight/App Store (production) builds: the app tells the server which it is.

Without the key the API runs normally and sends nothing. Check with `docker logs ridersapi` (lines starting "Ride Together push").

### Android (FCM)

1. Firebase console > project settings > Service accounts > Generate new private key. Save the JSON as `fcm.json` next to `apns.p8` (`chmod 600`).
2. Put `FCM_PROJECT_ID` (the project id from the same page) in `.env`.
3. Run `./deploy.sh`. Without `fcm.json` Android devices register but get nothing.
4. In the Android app add `FCM_PROJECT_ID`, `FCM_APP_ID`, `FCM_API_KEY` and `FCM_SENDER_ID` to `local.properties` (values from the Firebase app settings).

## Invite links for messengers (Viber, WhatsApp, ...)

Invites are `https://ridersapi.sportco.net/join/CODE`. The API serves:
- `/join/CODE`: a page for people without the app ("X invited you", Open in RiDelirium, the code, store buttons when `APP_STORE_URL` / `PLAY_STORE_URL` are set).
- `/.well-known/apple-app-site-association`: needs `APNS_TEAM_ID` and `APNS_TOPIC` in `.env`; makes the link open the iPhone app.
- `/.well-known/assetlinks.json`: needs `ANDROID_CERT_SHA256` (release signing certificate, comma separated for several); makes the link open the Android app.

Check: `curl -i https://ridersapi.sportco.net/.well-known/apple-app-site-association` must return 200 JSON with no redirect.
Universal links only work in builds signed with the Associated Domains capability, not in the simulator without it; Apple caches the file, so allow a day after changing it.

## Deploy script

`./deploy.sh` (in `Server/`) lints the PHP, copies `Dockerfile`, `docker-compose.yml` and `api/` to the NAS over SSH, rebuilds the container and checks it answers. It leaves the NAS's `.env` and `apns.p8` alone. `api/schema.sql` is the full schema for a fresh database; it is not run by the script.

# Android vs iOS gaps

iOS is the reference. Compared at feature level, not behaviour (thresholds, layout details).

## Missing on Android

| iOS | Android | Status |
|---|---|---|
| Settings tab and screen: name, METRIC/IMPERIAL toggle, About. Track screen has a Settings button. | None. `domain/Units.kt` has imperial code but no UI sets it. Name is only editable in the Ride Together sheet. | Done (build + lint pass, not run on a device) |
| About screen: app name, version, tagline. | None. | Done (build + lint pass, not run on a device) |
| Insights: "Ride insights" in daily stats, on-device AI text, tips, the numbers behind it (`InsightsSheet`, `RideInsights`). | Sheet with the numbers-only observations (fastest run, time split, longest stop, lifts, descent per hour) and an Insights button. No AI text: Android has no equivalent on-device model. | Done, numbers only (build + lint pass, not run on a device) |
| CSV format 3: unix-time timestamps, accuracy/course/activity columns, empty fields for invalid values, version and rejected-fix comment lines. | `TrackCsv.kt`, Room migration 1→2. Old points export with empty new columns. | Done (build + lint + unit tests pass, not run on a device) |
| Fix filter (accuracy, stale timestamps) with rejected counts, noise-sized steps skipped in distance. | `FixFilter`, `DistanceRule` in `TrackCsv.kt`; used by `TrackingEngine`. | Done (same) |
| Auto start when driving / auto stop when idle (off by default), activity stored per point. | Play services activity recognition (`MotionMonitor`), Settings switches. Auto start fires from the background, where Android 12+ may refuse a foreground service; not verified on a device. | Done, needs device check |
| Stats time filter (TODAY, 3 DAYS, 7 DAYS, 1 MONTH, SEASON, ALL) and progress insights for the rides in view. | `StatsRange.kt`, `PeriodInsights.kt`. Numbers only, no AI text. | Done, numbers only |
| Settings as a round button beside the tab bar; EXPORT button in the same grey as REPLAY and SHARE. | `AppRoot.kt`, `DailyStatsScreen.kt`. The profile chip on Track is gone. | Done |
| Far-away friend markers with initials. | `RiderTag` in `TrackScreen.kt`: coloured dot with initials below map zoom 13 (iOS: camera distance over 8 km). | Done (build + lint pass, not run on a device) |
| Rider tag on the map: sport glyph, speed / STOPPED / LAST SEEN, grey after 2 minutes. | `RiderTag`; `Friend.speedKmh` and `updatedAtMs` come from the API; Android now sends its own `speedKmh` in the heartbeat. Glyph is a drawn snowboard, not the iOS one. | Done (same) |
| Tap a rider on the map: card with distance and WAIT. | `SelectedFriendCard`; SENT state lasts 30 s like iOS. | Done (same) |
| Ride Together sheet: crew list first (status dot, distance, RIDING/STOPPED/LAST SEEN, WAIT/SENT, map pin), code card, SHARE CREW / JOIN CREW, second page with segmented SHARE/JOIN, auto-dash code field, LET'S RIDE screen. | `RideTogetherSheet.kt`; `FriendsRepository.focusOnMap` drives the map pin. Distance arrow uses course while moving (else north-up, like iOS). RECENT RIDERS via `GET riders/recent` and `POST riders/{id}/invite`. Camera scanner opens Google's scan screen, not inline. | Done (compiles, not run on a device) |
| Crew pill on Track: avatars, "RIDING WITH X +n", "n ON THE MAP · n OFFLINE". | `RideTogetherPill` in `TrackScreen.kt`. | Done (same) |
| Track screen layout: compact recording card with grabber, ALTITUDE only before a ride, GPS status inside START TRACKING, round Show everyone / Re-centre / Follow buttons. | `TrackScreen.kt`. | Done (same) |
| Apple Health downhill distance in daily stats (filled by the watch workout). | None. Health Connect is the closest equivalent. | Todo |
| "Show runs and lifts" row in daily stats. | Already there: the breakdown row opens Segments, same as iOS. Earlier list was wrong. | Not a gap |
| Nearby join: two phones pair over Bluetooth/Wi-Fi while a QR code shows (`NearbyRide`). | QR and code join only, via server. | Later |
| Push notifications: "joined / left your crew" alert, device token. Also "wait for me" and "X invites you". | FCM: `push/` (PushService, CrewNotifications, WaitReceiver, PushSetup), WAIT FOR ME button in the Ride Together sheet, `Server/api/push.php` sends to Android. Needs `FCM_*` in `local.properties` and `fcm.json` + `FCM_PROJECT_ID` on the server (see `Server/README.md`). | Done in code (build + lint pass), needs a Firebase project and a device check |
| Live Activity (lock screen / Dynamic Island) for the ride. | Foreground-service notification only. | Later |
| Apple Watch app: ride tracking, workout, phone link. | None. Wear OS would be a separate project. | Later |
| Pause/resume while tracking. | In the code; not confirmed to match iOS. | Verify |
| Keychain storage of the profile. | Not checked. | Verify |

## Same on both

Track, Stats, daily stats, Replay, Share, Export, Segments, Ride Together with QR and name, invite deep links.

## Android only

None. Group Voice (LiveKit) was removed from Android so it matches iOS: code, LiveKit dependency, service, permissions, build config. `Server/token.php` and the Web pages (`index.html`, `support.html`, `privacy.html`) still mention it.

## Platform differences, not gaps

iOS liquid-glass tab bar vs Android solid red bar.

## Suggested order

1. Create the Firebase project and test push on a device.
2. Verify pause/resume and profile storage against iOS.
3. Skip or postpone Nearby join, Live Activity, Watch.

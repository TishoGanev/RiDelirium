<?php
// Push notifications for Ride Together: tells a rider when a friend joins or leaves. Loaded by index.php only.
// iOS goes through APNs with a token-based (.p8) key. Android goes through FCM HTTP v1 with a service-account
// JSON key (FCM_KEY_FILE). Android gets data-only messages; the app builds the notification itself.

declare(strict_types=1);

if (!function_exists('cfg')) {
    http_response_code(404);
    exit;
}

function b64url(string $raw): string {
    return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
}

/** ES256 provider token for APNs. Apple wants it reused for up to an hour and refused when refreshed too often. */
function apnsJwt(string $keyFile, string $keyId, string $teamId): ?string {
    $cache = sys_get_temp_dir() . '/apns-jwt-' . hash('sha256', $keyId . $teamId);
    if (is_file($cache) && time() - filemtime($cache) < 2400) {
        return file_get_contents($cache) ?: null;
    }
    $pem = @file_get_contents($keyFile);
    $key = $pem ? openssl_pkey_get_private($pem) : false;
    if (!$key) {
        error_log('Ride Together push: cannot read APNs key');
        return null;
    }
    $signing = b64url(json_encode(['alg' => 'ES256', 'kid' => $keyId])) . '.' . b64url(json_encode(['iss' => $teamId, 'iat' => time()]));
    if (!openssl_sign($signing, $der, $key, OPENSSL_ALGO_SHA256)) {
        return null;
    }
    // OpenSSL returns DER (SEQUENCE of two INTEGERs); JWT wants the raw 32-byte r and s.
    $offset = 3;
    $rLen = ord($der[$offset]);
    $r = substr($der, $offset + 1, $rLen);
    $offset += $rLen + 2;
    $sLen = ord($der[$offset]);
    $s = substr($der, $offset + 1, $sLen);
    $raw = str_pad(ltrim($r, "\0"), 32, "\0", STR_PAD_LEFT) . str_pad(ltrim($s, "\0"), 32, "\0", STR_PAD_LEFT);
    $jwt = $signing . '.' . b64url($raw);
    file_put_contents($cache, $jwt, LOCK_EX);
    return $jwt;
}

/** Sends one alert to a device; returns false when APNs says the token is dead. */
function sendApns(string $deviceToken, bool $sandbox, array $payload, string $pushType = 'alert', string $topicSuffix = '', int $priority = 10): bool {
    $keyFile = cfg('APNS_KEY_FILE');
    $keyId = cfg('APNS_KEY_ID');
    $teamId = cfg('APNS_TEAM_ID');
    $topic = cfg('APNS_TOPIC');
    if (!$keyFile || !$keyId || !$teamId || !$topic) {
        return true; // push not configured: nothing to do, and not the token's fault
    }
    $jwt = apnsJwt($keyFile, $keyId, $teamId);
    if ($jwt === null) {
        return true;
    }
    $host = $sandbox ? 'api.sandbox.push.apple.com' : 'api.push.apple.com';
    $ch = curl_init("https://$host/3/device/$deviceToken");
    curl_setopt_array($ch, [
        CURLOPT_HTTP_VERSION => CURL_HTTP_VERSION_2_0,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 5,
        CURLOPT_HTTPHEADER => [
            "authorization: bearer $jwt",
            'apns-topic: ' . $topic . $topicSuffix,
            "apns-push-type: $pushType",
            "apns-priority: $priority",
            'apns-expiration: ' . (time() + 3600),
        ],
    ]);
    $response = curl_exec($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $curlError = curl_error($ch);
    curl_close($ch);
    // What Apple answered, for pushtest.php.
    $GLOBALS['apnsLast'] = ['status' => $status, 'reason' => is_string($response) ? (json_decode($response, true)['reason'] ?? '') : '', 'curl' => $curlError, 'host' => $host];
    if ($status === 200) {
        return true;
    }
    $reason = is_string($response) ? (json_decode($response, true)['reason'] ?? '') : 'no response';
    error_log("Ride Together push: APNs $status $reason");
    return !($status === 410 || in_array($reason, ['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic'], true));
}

/** OAuth2 access token for FCM from the service-account JSON; cached for most of its hour. */
function fcmAccessToken(string $keyFile): ?string {
    $cache = sys_get_temp_dir() . '/fcm-token-' . hash('sha256', $keyFile);
    if (is_file($cache) && time() - filemtime($cache) < 3000) {
        return file_get_contents($cache) ?: null;
    }
    $account = json_decode((string)@file_get_contents($keyFile), true);
    $key = is_array($account) && isset($account['private_key']) ? openssl_pkey_get_private($account['private_key']) : false;
    if (!$key) {
        error_log('Ride Together push: cannot read FCM key');
        return null;
    }
    $now = time();
    $signing = b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . b64url(json_encode([
        'iss' => $account['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => 'https://oauth2.googleapis.com/token',
        'iat' => $now,
        'exp' => $now + 3600,
    ]));
    if (!openssl_sign($signing, $signature, $key, OPENSSL_ALGO_SHA256)) {
        return null;
    }
    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => http_build_query(['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $signing . '.' . b64url($signature)]),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 5,
    ]);
    $response = json_decode((string)curl_exec($ch), true);
    curl_close($ch);
    $token = $response['access_token'] ?? null;
    if (!is_string($token)) {
        error_log('Ride Together push: FCM token request failed');
        return null;
    }
    file_put_contents($cache, $token, LOCK_EX);
    return $token;
}

/** Sends a data message to an Android device; returns false when FCM says the token is dead. */
function sendFcm(string $deviceToken, array $data): bool {
    $keyFile = cfg('FCM_KEY_FILE');
    $project = cfg('FCM_PROJECT_ID');
    if (!$keyFile || !$project || !is_file($keyFile) || filesize($keyFile) < 10) {
        return true; // push not configured: nothing to do, and not the token's fault
    }
    $access = fcmAccessToken($keyFile);
    if ($access === null) {
        return true;
    }
    $ch = curl_init("https://fcm.googleapis.com/v1/projects/$project/messages:send");
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode(['message' => [
            'token' => $deviceToken,
            // FCM data values must be strings.
            'data' => array_map('strval', $data),
            'android' => ['priority' => 'HIGH', 'ttl' => '3600s'],
        ]], JSON_UNESCAPED_UNICODE),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 5,
        CURLOPT_HTTPHEADER => ["authorization: Bearer $access", 'content-type: application/json'],
    ]);
    $response = curl_exec($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($status === 200) {
        return true;
    }
    error_log("Ride Together push: FCM $status " . (is_string($response) ? substr($response, 0, 200) : 'no response'));
    return !($status === 404 || $status === 400 && is_string($response) && str_contains($response, 'INVALID_ARGUMENT'));
}

/**
 * Sends the push described by an APNs-shaped `$payload` to one device row. The same payload serves Android:
 * its alert text and custom keys (event, code, waitId) become a data message. False means the token is dead.
 */
function deliver(array $device, array $payload): bool {
    if ($device['platform'] === 'ios') {
        return sendApns($device['token'], (bool)$device['sandbox'], $payload);
    }
    $alert = $payload['aps']['alert'] ?? [];
    $data = array_filter(['title' => $alert['title'] ?? null, 'body' => $alert['body'] ?? null] + array_diff_key($payload, ['aps' => 1]), fn($v) => $v !== null);
    return sendFcm($device['token'], $data);
}

/**
 * Tells everyone who shares a crew with `$riderId` that something changed (their sport or name, a crew's name), with no alert,
 * so open apps refresh their crew at once. Failures are logged, never thrown.
 */
function notifyCrewChanged(string $riderId, string $event): void {
    try {
        $friends = array_column(crewMatesOf($riderId), 'id');
        foreach ($friends as $friendId) {
            foreach (q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$friendId])->fetchAll() as $device) {
                // iOS: a silent background push (priority 5 is required for those); Android: a data-only message.
                $alive = $device['platform'] === 'ios'
                    ? sendApns($device['token'], (bool)$device['sandbox'], ['aps' => ['content-available' => 1], 'event' => $event], 'background', '', 5)
                    : sendFcm($device['token'], ['event' => $event]);
                if (!$alive) {
                    q('DELETE FROM devices WHERE token = ?', [$device['token']]);
                }
            }
        }
    } catch (Throwable $e) {
        error_log('Ride Together push: ' . $e->getMessage());
    }
}

/** Tells `$riderId` that `$who` joined or left `$crew`. Failures are logged, never thrown. */
function notifyRider(string $riderId, string $event, string $who, string $crew): void {
    $crew = mb_strtoupper($crew);
    $body = $event === 'join' ? "$who joined $crew" : "$who left $crew";
    $payload = [
        'aps' => ['alert' => ['title' => 'RiDelirium', 'body' => $body], 'sound' => 'default', 'thread-id' => 'crew'],
        'event' => $event,
    ];
    try {
        foreach (q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$riderId])->fetchAll() as $device) {
            if (!deliver($device, $payload)) {
                q('DELETE FROM devices WHERE token = ?', [$device['token']]);
            }
        }
    } catch (Throwable $e) {
        error_log('Ride Together push: ' . $e->getMessage());
    }
}

/** Swift's ContentState for a Live Activity update; the app fills in the ride fields on its next update. */
function waitContentState(array $row, string $state, int $count): array {
    $request = [
        'id' => $row['id'],
        'fromName' => $row['name'],
        'initials' => $row['initials'],
        'colorIndex' => colorIndex($row['from_rider']),
        'sentAt' => (float)time(),
        'count' => $count,
    ];
    if ($row['distance_m'] !== null) {
        $request['distanceM'] = (float)$row['distance_m'];
    }
    return ['request' => $request];
}

/** Same index the apps compute from a rider id, so a friend keeps one colour everywhere. */
function colorIndex(string $riderId): int {
    $hash = 0;
    foreach (preg_split('//u', $riderId, -1, PREG_SPLIT_NO_EMPTY) as $ch) {
        $hash = (($hash * 31) + mb_ord($ch)) & 0xFFFF;
    }
    return $hash % 5;
}

/**
 * Tells `$riderId` that a friend asks them to wait. Two pushes, because either can be missing: a Live Activity
 * update that takes over the lock screen and Dynamic Island, and a normal alert (also shown on a paired watch).
 */
function notifyWait(string $riderId, array $wait, ?int $meters): void {
    $who = mb_strtoupper($wait['name']);
    $body = "$who: WAIT FOR ME" . ($meters !== null ? " · $meters M" : '');
    try {
        $activity = q('SELECT token, sandbox FROM live_activities WHERE rider_id = ?', [$riderId])->fetch();
        if ($activity) {
            $pending = (int)q('SELECT COUNT(*) FROM waits WHERE to_rider = ? AND accepted_at IS NULL AND created_at > UTC_TIMESTAMP() - INTERVAL ' . WAIT_TTL . ' SECOND', [$riderId])->fetchColumn();
            $payload = ['aps' => [
                'timestamp' => time(),
                'event' => 'update',
                'content-state' => waitContentState($wait, 'request', max(1, $pending)),
                'stale-date' => time() + WAIT_TTL,
                'alert' => ['title' => 'RiDelirium', 'body' => $body],
                'sound' => 'default',
            ]];
            if (!sendApns($activity['token'], (bool)$activity['sandbox'], $payload, 'liveactivity', '.push-type.liveactivity')) {
                q('DELETE FROM live_activities WHERE rider_id = ?', [$riderId]);
            }
        }
        $payload = [
            // The category gives the alert an "OK, waiting" button on the iPhone and the watch.
            'aps' => ['alert' => ['title' => 'RiDelirium', 'body' => $body], 'sound' => 'default', 'thread-id' => 'wait', 'interruption-level' => 'time-sensitive', 'category' => 'WAIT'],
            'event' => 'wait',
            'waitId' => $wait['id'],
        ];
        foreach (q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$riderId])->fetchAll() as $device) {
            if (!deliver($device, $payload)) {
                q('DELETE FROM devices WHERE token = ?', [$device['token']]);
            }
        }
    } catch (Throwable $e) {
        error_log('Ride Together push: ' . $e->getMessage());
    }
}

/** The friend answered: tell the sender they are being waited for. */
function notifyWaitAccepted(string $riderId, string $who): void {
    $payload = [
        'aps' => ['alert' => ['title' => 'RiDelirium', 'body' => mb_strtoupper($who) . ' IS WAITING FOR YOU'], 'sound' => 'default', 'thread-id' => 'wait'],
        'event' => 'wait-accepted',
    ];
    try {
        foreach (q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$riderId])->fetchAll() as $device) {
            if (!deliver($device, $payload)) {
                q('DELETE FROM devices WHERE token = ?', [$device['token']]);
            }
        }
    } catch (Throwable $e) {
        error_log('Ride Together push: ' . $e->getMessage());
    }
}

/** A recent riding partner asks `$riderId` to join their crew; tapping the notification opens the app on the code. */
function notifyInvite(string $riderId, string $who, string $code, string $crew): void {
    $payload = [
        'aps' => ['alert' => ['title' => 'RiDelirium', 'body' => mb_strtoupper($who) . ' INVITES YOU TO ' . mb_strtoupper($crew)], 'sound' => 'default', 'thread-id' => 'invite'],
        'event' => 'invite',
        'code' => $code,
    ];
    try {
        foreach (q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$riderId])->fetchAll() as $device) {
            if (!deliver($device, $payload)) {
                q('DELETE FROM devices WHERE token = ?', [$device['token']]);
            }
        }
    } catch (Throwable $e) {
        error_log('Ride Together push: ' . $e->getMessage());
    }
}

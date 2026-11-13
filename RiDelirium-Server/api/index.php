<?php
// Ride Together API: accountless friends list for the RiDelirium app.
//
//   POST   /v1/riders                {"id","name"}        -> 201 {"token"}      register this install
//   PUT    /v1/riders/me             {"name"}             -> 200 friend         change display name
//   DELETE /v1/riders/me                                  -> 204                delete everything about me
//   POST   /v1/invites                                    -> 200 {"code"}       my invite code (stable)
//   POST   /v1/invites/{code}/join                        -> 200 friend         join its owner's crew: friends with the owner and everyone they ride with
//   GET    /v1/friends                                    -> 200 [friend]
//   GET    /v1/riders/recent                              -> 200 [{id,displayName,initials}]  people I rode with in the last 30 days, not in my crew now
//   POST   /v1/riders/{id}/invite                         -> 204                push "invites you to ride together" with my code
//   DELETE /v1/friends/{id}                               -> 204
//   POST   /v1/friends/{id}/wait     {"distanceMeters"?}  -> 201 {id}           ask a friend to wait; stored, then pushed (Live Activity + alert)
//   GET    /v1/waits/incoming                             -> 200 [request]      requests waiting for my answer (newer than WAIT_TTL)
//   POST   /v1/waits/{id}/accept                          -> 204                "OK, waiting"; the sender is notified
//   GET    /v1/waits/{id}                                 -> 200 {status}       sender: pending | accepted | expired
//   PUT    /v1/live-activity         {"token","sandbox"}  -> 204                push token of my running Live Activity
//   DELETE /v1/live-activity                              -> 204
//   PUT    /v1/live                  {"riding":bool,"lat","lon","speedKmh"} -> 204   heartbeat; "riding" expires after 90 seconds.
//                                                                                Friends see the latest position for 10 minutes after
//                                                                                the last heartbeat ("last seen"), or until riding:false
//   GET    /join/{code}                                   -> HTML invite page; /.well-known/ files make the link open the apps
//   PUT    /v1/push                  {"platform":"ios","token","sandbox":bool} -> 204  where to send "joined / left your crew"
//   DELETE /v1/push                  {"token"}            -> 204                stop notifying this device
//   GET    /v1/live/friends                               -> 200 [friend]       friends riding right now
//
// friend = {"id","displayName","initials","isRiding"} plus "lat","lon","speedKmh","ageSeconds" (seconds since the
// last heartbeat) while a recent position is known. Everything but registering needs
// "Authorization: Bearer <token>". Needs PHP 8.0+ with PDO MySQL. See schema.sql for what is stored.

declare(strict_types=1);

ini_set('serialize_precision', '-1'); // shortest exact float, so 42.69771 stays 42.69771 in JSON
header('Content-Type: application/json');
header('Cache-Control: no-store');

const MAX_FRIENDS = 50;
const RIDING_TTL = 90;
const WAIT_TTL = 300; // seconds a "wait for me" stays open before it counts as unanswered
const POSITION_TTL = 600; // how long a friend can still see where you were last
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

$config = file_exists(__DIR__ . '/../config.php') ? require __DIR__ . '/../config.php' : [];
function cfg(string $key): ?string {
    global $config;
    $value = $config[$key] ?? getenv($key);
    return $value === false || $value === '' ? null : (string)$value;
}

/** @var callable[] Work to do after the response has gone out, such as sending push notifications. */
$afterResponse = [];

function afterResponse(callable $work): void {
    global $afterResponse;
    $afterResponse[] = $work;
}

function respond(int $status, mixed $body = null): never {
    global $afterResponse;
    http_response_code($status);
    $json = $body !== null ? json_encode($body, JSON_UNESCAPED_UNICODE) : '';
    if ($afterResponse) {
        // Let the app have its answer before we spend time talking to Apple.
        header('Connection: close');
        if ($status !== 204) {
            header('Content-Length: ' . strlen($json));
        }
    }
    echo $json;
    if ($afterResponse) {
        ignore_user_abort(true);
        while (ob_get_level() > 0) {
            ob_end_flush();
        }
        flush();
        foreach ($afterResponse as $work) {
            try {
                $work();
            } catch (Throwable $e) {
                error_log('Ride Together API: ' . $e->getMessage());
            }
        }
    }
    exit;
}

function fail(int $status, string $message): never {
    respond($status, ['error' => $message]);
}

function db(): PDO {
    static $pdo = null;
    if ($pdo === null) {
        $dsn = cfg('DB_DSN');
        if ($dsn === null) {
            fail(500, 'Server is not configured');
        }
        $pdo = new PDO($dsn, cfg('DB_USER'), cfg('DB_PASS'), [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        $pdo->exec("SET time_zone = '+00:00'");
    }
    return $pdo;
}

function q(string $sql, array $params = []): PDOStatement {
    $stmt = db()->prepare($sql);
    $stmt->execute($params);
    return $stmt;
}

/** Allows `$limit` calls per `$window` seconds for this caller and scope. */
function rateLimit(string $scope, string $who, int $limit, int $window): void {
    $key = hash('sha256', (cfg('RATE_SALT') ?? 'change-me') . '|' . $scope . '|' . $who);
    $bucket = intdiv(time(), $window);
    q('INSERT INTO rate_limits (k, bucket) VALUES (?, ?) ON DUPLICATE KEY UPDATE n = n + 1', [$key, $bucket]);
    $n = (int)q('SELECT n FROM rate_limits WHERE k = ? AND bucket = ?', [$key, $bucket])->fetchColumn();
    if ($n > $limit) {
        header('Retry-After: ' . $window);
        fail(429, 'Too many requests');
    }
}

function cleanName(mixed $raw): string {
    $name = is_string($raw) ? trim($raw) : '';
    $name = mb_substr($name, 0, 30);
    if ($name === '' || preg_match('/[\x00-\x1F\x7F]/', $name)) {
        fail(400, 'Name must be 1 to 30 characters');
    }
    return $name;
}

/** Same rule as the apps: first letter of up to two words, "ME" when there are none. */
function initials(string $name): string {
    $letters = '';
    foreach (array_slice(preg_split('/\s+/u', $name, -1, PREG_SPLIT_NO_EMPTY), 0, 2) as $word) {
        $letters .= mb_substr($word, 0, 1);
    }
    return $letters === '' ? 'ME' : mb_strtoupper($letters);
}

function friendJson(array $row): array {
    $friend = [
        'id' => $row['id'],
        'displayName' => $row['name'],
        'initials' => $row['initials'],
        'isRiding' => (bool)$row['riding'],
    ];
    if (isset($row['lat'], $row['lon'], $row['age']) && (int)$row['age'] <= POSITION_TTL) {
        $friend['lat'] = (float)$row['lat'];
        $friend['lon'] = (float)$row['lon'];
        $friend['speedKmh'] = round((float)($row['speed'] ?? 0), 1);
        $friend['ageSeconds'] = (int)$row['age'];
    }
    return $friend;
}

/** The signed-in rider's row. */
function authenticate(): array {
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('getallheaders')) {
        $header = array_change_key_case(getallheaders())['authorization'] ?? '';
    }
    if (!preg_match('/^Bearer ([0-9a-f]{64})$/', $header, $m)) {
        fail(401, 'Missing or invalid token');
    }
    $rider = q('SELECT id, name, initials, 0 AS riding FROM riders WHERE token_hash = ?', [hash('sha256', $m[1])])->fetch();
    if (!$rider) {
        fail(401, 'Missing or invalid token');
    }
    q('UPDATE riders SET last_seen = UTC_DATE() WHERE id = ? AND last_seen < UTC_DATE()', [$rider['id']]);
    rateLimit('rider', $rider['id'], 2000, 3600);
    return $rider;
}

require __DIR__ . '/push.php';

function body(): array {
    $data = json_decode(file_get_contents('php://input', false, null, 0, 4096) ?: '', true);
    return is_array($data) ? $data : [];
}

function friendsOf(string $me, bool $ridingOnly = false): array {
    $rows = q(
        'SELECT r.id, r.name, r.initials, (r.riding_until > UTC_TIMESTAMP()) AS riding, r.lat, r.lon, r.speed,
                TIMESTAMPDIFF(SECOND, r.seen_at, UTC_TIMESTAMP()) AS age
           FROM friendships f
           JOIN riders r ON r.id = IF(f.rider_a = ?, f.rider_b, f.rider_a)
          WHERE f.rider_a = ? OR f.rider_b = ?
          ORDER BY r.name',
        [$me, $me, $me]
    )->fetchAll();
    $friends = array_map('friendJson', $rows);
    return $ridingOnly ? array_values(array_filter($friends, fn ($f) => $f['isRiding'])) : $friends;
}

/** Remembers that two riders were in a crew together, for "recent riders". */
function touchRiddenWith(string $a, string $b): void {
    [$a, $b] = $a < $b ? [$a, $b] : [$b, $a];
    q('REPLACE INTO ridden_with (rider_a, rider_b, last_at) VALUES (?, ?, UTC_TIMESTAMP())', [$a, $b]);
}

function newCode(): string {
    for ($try = 0; $try < 10; $try++) {
        $code = '';
        for ($i = 0; $i < 8; $i++) {
            $code .= CODE_ALPHABET[random_int(0, strlen(CODE_ALPHABET) - 1)];
        }
        if (!q('SELECT 1 FROM invites WHERE code = ?', [$code])->fetchColumn()) {
            return $code;
        }
    }
    fail(500, 'Could not create a code');
}

// MARK: - Invite links shared in messengers

/** https://<host>/join/<code> opens the app when installed (universal link / app link), else this page. */
function invitePage(string $code): never {
    rateLimit('page', $_SERVER['REMOTE_ADDR'] ?? '', 120, 3600);
    $owner = q('SELECT r.name FROM invites i JOIN riders r ON r.id = i.rider_id WHERE i.code = ?', [strtoupper($code)])->fetchColumn();
    $e = fn (string $text): string => htmlspecialchars($text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $code = strtoupper($code);
    $title = $owner ? $owner . ' invited you to ride on RiDelirium' : 'Ride together on RiDelirium';
    $text = $owner ? 'Join their crew to see each other on the map while you ride.' : 'This invite link is no longer valid. Ask for a new one.';
    $stores = '';
    foreach (['APP_STORE_URL' => 'Download for iPhone', 'PLAY_STORE_URL' => 'Download for Android'] as $key => $label) {
        if ($url = cfg($key)) {
            $stores .= '<a class="store" href="' . $e($url) . '">' . $label . '</a>';
        }
    }
    header('Content-Type: text/html; charset=utf-8');
    header('Cache-Control: no-cache');
    http_response_code($owner ? 200 : 404);
    echo '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        . '<title>' . $e($title) . '</title>'
        . '<meta property="og:title" content="' . $e($title) . '"><meta property="og:description" content="' . $e($text) . '">'
        . '<meta name="robots" content="noindex">'
        . '<style>body{font:17px/1.4 -apple-system,system-ui,sans-serif;margin:0;padding:48px 24px;text-align:center;color:#353535}'
        . 'h1{font-size:24px;margin:0 0 12px}p{color:#6b6b6b;margin:0 0 24px}'
        . 'a.btn,a.store{display:block;max-width:320px;margin:12px auto;padding:16px;border-radius:16px;text-decoration:none;font-weight:600}'
        . 'a.btn{background:#ed213d;color:#fff}a.store{background:#eee;color:#353535}'
        . '.code{font:600 28px ui-monospace,monospace;letter-spacing:4px;margin:8px 0 4px}</style></head><body>'
        . '<h1>' . $e($title) . '</h1><p>' . $e($text) . '</p>';
    if ($owner) {
        echo '<a class="btn" href="ridelirium://join/' . $e($code) . '">Open in RiDelirium</a>' . $stores
            . '<p style="margin-top:32px">No link button? In RiDelirium open Ride Together, tap Scan, then Type a code:</p>'
            . '<div class="code">' . $e(substr($code, 0, 4) . '-' . substr($code, 4)) . '</div>';
    }
    echo '</body></html>';
    exit;
}

$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

if (preg_match('#^/join/([A-Za-z0-9]{8})/?$#', $path, $m) && $_SERVER['REQUEST_METHOD'] === 'GET') {
    try {
        invitePage($m[1]);
    } catch (PDOException $e) {
        error_log('Ride Together API: ' . $e->getMessage());
        fail(500, 'Server error');
    }
}
if ($path === '/.well-known/apple-app-site-association' && cfg('APNS_TEAM_ID') && cfg('APNS_TOPIC')) {
    // Must be served as plain JSON over https without a redirect.
    respond(200, ['applinks' => ['details' => [[
        'appIDs' => [cfg('APNS_TEAM_ID') . '.' . cfg('APNS_TOPIC')],
        'components' => [['/' => '/join/*']],
    ]]]]);
}
if ($path === '/.well-known/assetlinks.json' && cfg('ANDROID_CERT_SHA256')) {
    respond(200, [[
        'relation' => ['delegate_permission/common.handle_all_urls'],
        'target' => [
            'namespace' => 'android_app',
            'package_name' => cfg('ANDROID_PACKAGE') ?? 'com.bgdeveloper.ridelirium',
            'sha256_cert_fingerprints' => array_map('trim', explode(',', cfg('ANDROID_CERT_SHA256'))),
        ],
    ]]);
}

if (!preg_match('#/v1(/[^?]*)?$#', $path, $m)) {
    fail(404, 'Not found');
}
$route = rtrim($m[1] ?? '/', '/') ?: '/';
$method = $_SERVER['REQUEST_METHOD'];

try {
    if ($route === '/riders' && $method === 'POST') {
        rateLimit('register', $_SERVER['REMOTE_ADDR'] ?? '', 10, 3600);
        $in = body();
        $id = $in['id'] ?? '';
        if (!is_string($id) || !preg_match('/^[0-9A-Fa-f-]{36}$/', $id)) {
            fail(400, 'Invalid id');
        }
        $id = strtoupper($id);
        $name = cleanName($in['name'] ?? null);
        if (q('SELECT 1 FROM riders WHERE id = ?', [$id])->fetchColumn()) {
            fail(409, 'Already registered');
        }
        $token = bin2hex(random_bytes(32));
        q('INSERT INTO riders (id, token_hash, name, initials, last_seen) VALUES (?, ?, ?, ?, UTC_DATE())',
            [$id, hash('sha256', $token), $name, initials($name)]);
        respond(201, ['token' => $token]);
    }

    $me = authenticate();

    if ($route === '/riders/me' && $method === 'PUT') {
        $name = cleanName(body()['name'] ?? null);
        q('UPDATE riders SET name = ?, initials = ? WHERE id = ?', [$name, initials($name), $me['id']]);
        respond(200, friendJson(['id' => $me['id'], 'name' => $name, 'initials' => initials($name), 'riding' => 0]));
    }

    if ($route === '/riders/me' && $method === 'DELETE') {
        q('DELETE FROM riders WHERE id = ?', [$me['id']]); // invites and friendships cascade
        respond(204);
    }

    if ($route === '/invites' && $method === 'POST') {
        $code = q('SELECT code FROM invites WHERE rider_id = ?', [$me['id']])->fetchColumn();
        if (!$code) {
            $code = newCode();
            q('INSERT INTO invites (code, rider_id) VALUES (?, ?)', [$code, $me['id']]);
        }
        respond(200, ['code' => $code]);
    }

    if (preg_match('#^/invites/([A-Za-z0-9]{8})/join$#', $route, $r) && $method === 'POST') {
        // Codes are a shared secret; keep guessing slow.
        rateLimit('join', $me['id'], 20, 3600);
        $owner = q(
            'SELECT r.id, r.name, r.initials, (r.riding_until > UTC_TIMESTAMP()) AS riding, r.lat, r.lon, r.speed,
                    TIMESTAMPDIFF(SECOND, r.seen_at, UTC_TIMESTAMP()) AS age
               FROM invites i JOIN riders r ON r.id = i.rider_id WHERE i.code = ?',
            [strtoupper($r[1])]
        )->fetch();
        if (!$owner) {
            fail(404, 'Unknown code');
        }
        if ($owner['id'] === $me['id']) {
            fail(400, 'That is your own code');
        }
        if (count(friendsOf($me['id'])) >= MAX_FRIENDS) {
            fail(409, 'Friend limit reached');
        }
        // A code opens its owner's whole crew: you become friends with the owner and everyone they ride with,
        // so the group sees each other on the map, and each of them hears that you joined.
        $crew = array_merge([$owner['id']], array_column(friendsOf($owner['id']), 'id'));
        $mine = count(friendsOf($me['id']));
        $notify = [];
        foreach (array_unique($crew) as $memberId) {
            if ($memberId === $me['id'] || $mine >= MAX_FRIENDS) {
                continue;
            }
            $theirs = (int)q('SELECT COUNT(*) FROM friendships WHERE rider_a = ? OR rider_b = ?', [$memberId, $memberId])->fetchColumn();
            if ($theirs >= MAX_FRIENDS) {
                continue;
            }
            [$a, $b] = $me['id'] < $memberId ? [$me['id'], $memberId] : [$memberId, $me['id']];
            if (q('INSERT IGNORE INTO friendships (rider_a, rider_b) VALUES (?, ?)', [$a, $b])->rowCount() > 0) {
                $mine++;
                $notify[] = $memberId;
                touchRiddenWith($a, $b);
            }
        }
        if ($notify) {
            afterResponse(function () use ($notify, $me) {
                foreach ($notify as $memberId) {
                    notifyRider($memberId, 'join', $me['name']);
                }
            });
        }
        respond(200, friendJson($owner));
    }

    // People I rode with in the last 30 days who are not in my crew now.
    if ($route === '/riders/recent' && $method === 'GET') {
        $rows = q(
            'SELECT r.id, r.name, r.initials
               FROM ridden_with w
               JOIN riders r ON r.id = IF(w.rider_a = ?, w.rider_b, w.rider_a)
              WHERE (w.rider_a = ? OR w.rider_b = ?) AND w.last_at > UTC_TIMESTAMP() - INTERVAL 30 DAY
                AND NOT EXISTS (SELECT 1 FROM friendships f WHERE f.rider_a = w.rider_a AND f.rider_b = w.rider_b)
              ORDER BY w.last_at DESC LIMIT 20',
            [$me['id'], $me['id'], $me['id']]
        )->fetchAll();
        respond(200, array_map(fn ($r) => ['id' => $r['id'], 'displayName' => $r['name'], 'initials' => $r['initials']], $rows));
    }

    // Invite a recent rider to my crew: a push with my code. Only people I rode with lately can be asked.
    if (preg_match('#^/riders/([0-9A-Fa-f-]{36})/invite$#', $route, $r) && $method === 'POST') {
        $other = strtoupper($r[1]);
        [$a, $b] = $me['id'] < $other ? [$me['id'], $other] : [$other, $me['id']];
        $recent = q('SELECT 1 FROM ridden_with WHERE rider_a = ? AND rider_b = ? AND last_at > UTC_TIMESTAMP() - INTERVAL 30 DAY', [$a, $b])->fetchColumn();
        if (!$recent) {
            fail(404, 'Not a recent rider');
        }
        rateLimit('invite', $me['id'] . '|' . $other, 3, 3600);
        $code = q('SELECT code FROM invites WHERE rider_id = ?', [$me['id']])->fetchColumn();
        if (!$code) {
            $code = newCode();
            q('INSERT INTO invites (code, rider_id) VALUES (?, ?)', [$code, $me['id']]);
        }
        afterResponse(fn () => notifyInvite($other, $me['name'], $code));
        respond(204);
    }

    if ($route === '/friends' && $method === 'GET') {
        respond(200, friendsOf($me['id']));
    }

    if (preg_match('#^/friends/([0-9A-Fa-f-]{36})$#', $route, $r) && $method === 'DELETE') {
        $other = strtoupper($r[1]);
        [$a, $b] = $me['id'] < $other ? [$me['id'], $other] : [$other, $me['id']];
        $removed = q('DELETE FROM friendships WHERE rider_a = ? AND rider_b = ?', [$a, $b])->rowCount() > 0;
        if ($removed) {
            touchRiddenWith($a, $b);
            afterResponse(fn () => notifyRider($other, 'leave', $me['name']));
        }
        respond(204);
    }

    if (preg_match('#^/friends/([0-9A-Fa-f-]{36})/wait$#', $route, $r) && $method === 'POST') {
        $other = strtoupper($r[1]);
        [$a, $b] = $me['id'] < $other ? [$me['id'], $other] : [$other, $me['id']];
        if (!q('SELECT 1 FROM friendships WHERE rider_a = ? AND rider_b = ?', [$a, $b])->fetchColumn()) {
            fail(404, 'Not your friend');
        }
        // A tap on the watch is one message; this only stops a stuck button from flooding someone.
        rateLimit('wait', $me['id'] . '|' . $other, 3, 60);
        $in = body();
        $meters = isset($in['distanceMeters']) && is_numeric($in['distanceMeters']) ? max(0, min(99999, (int)round((float)$in['distanceMeters']))) : null;
        $id = bin2hex(random_bytes(8));
        q('INSERT INTO waits (id, from_rider, to_rider, distance_m, created_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP())', [$id, $me['id'], $other, $meters]);
        $wait = ['id' => $id, 'from_rider' => $me['id'], 'name' => $me['name'], 'initials' => $me['initials'], 'distance_m' => $meters];
        afterResponse(fn () => notifyWait($other, $wait, $meters));
        respond(201, ['id' => $id]);
    }

    // Requests waiting for my answer, newest first. Older than WAIT_TTL they no longer count.
    if ($route === '/waits/incoming' && $method === 'GET') {
        $rows = q(
            'SELECT w.id, w.from_rider, r.name, r.initials, w.distance_m, TIMESTAMPDIFF(SECOND, w.created_at, UTC_TIMESTAMP()) AS age,
                    r.lat, r.lon, r.speed, TIMESTAMPDIFF(SECOND, r.seen_at, UTC_TIMESTAMP()) AS pos_age
               FROM waits w JOIN riders r ON r.id = w.from_rider
              WHERE w.to_rider = ? AND w.accepted_at IS NULL AND w.created_at > UTC_TIMESTAMP() - INTERVAL ' . WAIT_TTL . ' SECOND
              ORDER BY w.created_at DESC LIMIT 10',
            [$me['id']]
        )->fetchAll();
        respond(200, array_map(fn ($w) => [
            'id' => $w['id'],
            'fromId' => $w['from_rider'],
            'fromName' => $w['name'],
            'initials' => $w['initials'],
            'distanceM' => $w['distance_m'] === null ? null : (int)$w['distance_m'],
            'ageSeconds' => (int)$w['age'],
        ], $rows));
    }

    if (preg_match('#^/waits/([0-9a-f]{16})/accept$#', $route, $r) && $method === 'POST') {
        $wait = q('SELECT id, from_rider FROM waits WHERE id = ? AND to_rider = ?', [$r[1], $me['id']])->fetch();
        if (!$wait) {
            fail(404, 'No such request');
        }
        q('UPDATE waits SET accepted_at = UTC_TIMESTAMP() WHERE id = ? AND accepted_at IS NULL', [$r[1]]);
        afterResponse(fn () => notifyWaitAccepted($wait['from_rider'], $me['name']));
        respond(204);
    }

    // The sender asks how their request went: pending, accepted or expired.
    if (preg_match('#^/waits/([0-9a-f]{16})$#', $route, $r) && $method === 'GET') {
        $wait = q('SELECT accepted_at, created_at < UTC_TIMESTAMP() - INTERVAL ' . WAIT_TTL . ' SECOND AS old FROM waits WHERE id = ? AND from_rider = ?', [$r[1], $me['id']])->fetch();
        if (!$wait) {
            fail(404, 'No such request');
        }
        respond(200, ['status' => $wait['accepted_at'] !== null ? 'accepted' : ((int)$wait['old'] ? 'expired' : 'pending')]);
    }

    if ($route === '/live-activity' && $method === 'PUT') {
        $in = body();
        $token = $in['token'] ?? '';
        if (!is_string($token) || !preg_match('/^[0-9A-Fa-f]{20,512}$/', $token)) {
            fail(400, 'Invalid activity token');
        }
        q('REPLACE INTO live_activities (rider_id, token, sandbox, updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP())',
            [$me['id'], $token, !empty($in['sandbox']) ? 1 : 0]);
        respond(204);
    }

    if ($route === '/live-activity' && $method === 'DELETE') {
        q('DELETE FROM live_activities WHERE rider_id = ?', [$me['id']]);
        respond(204);
    }

    if ($route === '/push' && $method === 'PUT') {
        $in = body();
        $token = $in['token'] ?? '';
        if (!is_string($token) || !preg_match('/^[0-9A-Za-z:_-]{20,255}$/', $token) || !in_array($in['platform'] ?? '', ['ios', 'android'], true)) {
            fail(400, 'Invalid push token');
        }
        // A phone has one token at a time: a new one replaces the rider's old ones for that platform,
        // and a token that moved to another rider (new install) moves with it.
        q('DELETE FROM devices WHERE rider_id = ? AND platform = ?', [$me['id'], $in['platform']]);
        q('REPLACE INTO devices (token, rider_id, platform, sandbox) VALUES (?, ?, ?, ?)',
            [$token, $me['id'], $in['platform'], !empty($in['sandbox']) ? 1 : 0]);
        respond(204);
    }

    if ($route === '/push' && $method === 'DELETE') {
        q('DELETE FROM devices WHERE rider_id = ?', [$me['id']]);
        respond(204);
    }

    if ($route === '/live' && $method === 'PUT') {
        $in = body();
        $riding = $in['riding'] ?? null;
        if (!is_bool($riding)) {
            fail(400, 'riding must be true or false');
        }
        $lat = $lon = $speed = null;
        if ($riding && isset($in['lat'], $in['lon'])) {
            if (!is_numeric($in['lat']) || !is_numeric($in['lon']) || abs($in['lat']) > 90 || abs($in['lon']) > 180) {
                fail(400, 'Invalid position');
            }
            // Five decimals is about a metre; only the latest position is kept, never a history.
            $lat = round((float)$in['lat'], 5);
            $lon = round((float)$in['lon'], 5);
            $speed = isset($in['speedKmh']) && is_numeric($in['speedKmh']) ? min(max(round((float)$in['speedKmh'], 1), 0), 300) : 0;
        }
        // Stopping on purpose clears the position at once; losing signal leaves it as "last seen" for a while.
        q('UPDATE riders SET riding_until = ' . ($riding ? 'UTC_TIMESTAMP() + INTERVAL ' . RIDING_TTL . ' SECOND' : 'NULL')
            . ', lat = ?, lon = ?, speed = ?, seen_at = ' . ($riding ? 'UTC_TIMESTAMP()' : 'NULL') . ' WHERE id = ?',
            [$lat, $lon, $speed, $me['id']]);
        // Positions nobody has refreshed for ten minutes are not kept.
        q('UPDATE riders SET lat = NULL, lon = NULL, speed = NULL WHERE lat IS NOT NULL AND (seen_at IS NULL OR seen_at < UTC_TIMESTAMP() - INTERVAL ' . POSITION_TTL . ' SECOND)');
        respond(204);
    }

    if ($route === '/live/friends' && $method === 'GET') {
        respond(200, friendsOf($me['id'], ridingOnly: true));
    }

    fail(404, 'Not found');
} catch (PDOException $e) {
    error_log('Ride Together API: ' . $e->getMessage());
    fail(500, 'Server error');
}

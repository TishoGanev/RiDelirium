<?php
// Ride Together API: accountless crews for the RiDelirium app.
//
//   POST   /v1/riders                {"id","name"}        -> 201 {"token"}      register this install
//   PUT    /v1/riders/me             {"name"?,"sport"?}   -> 200 friend         change display name and/or sport (ski, board, bike, walk); crew-mates are told at once
//   DELETE /v1/riders/me                                  -> 204                delete everything about me
//   GET    /v1/crews                                      -> 200 [crew]         my crews (expired memberships are gone)
//   POST   /v1/crews                 {"name"}             -> 201 crew           create a crew; I am in it until I leave
//   PUT    /v1/crews/{id}            {"name"}             -> 200 crew           rename (owner only; PATCH works too)
//   GET    /v1/crews/preview/{code}                       -> 200 {name,ownerName,memberCount}  what a code opens, before joining
//   POST   /v1/crews/join            {"code","until"?}    -> 200 crew           join; until = epoch seconds (at most 30 days ahead), none = until I leave
//   PUT    /v1/crews/{id}/membership {"until"?}           -> 200 crew           change how long I stay
//   DELETE /v1/crews/{id}/membership                      -> 204                leave this crew
//   PUT    /v1/crews/{id}/meet       {"lat","lon","kind","inMinutes"} -> 200 crew  drop the crew's meet point (kind: lift, hut, parking, here;
//                                                                                inMinutes 0..120); the others get a push
//   DELETE /v1/crews/{id}/meet                            -> 200 crew           clear it
//   GET    /v1/friends                                    -> 200 [friend]       everyone I share an active crew with (for older builds)
//   GET    /v1/riders/recent                              -> 200 [{id,displayName,initials,sport}]  people I rode with in the last 30 days, in none of my crews now
//   POST   /v1/riders/{id}/invite    {"crewId"}           -> 204                push "invites you to <crew>" with that crew's code
//   POST   /v1/friends/{id}/wait     {"distanceMeters"?}  -> 201 {id}           ask a crew-mate to wait; stored, then pushed (Live Activity + alert)
//   GET    /v1/waits/incoming                             -> 200 [request]      requests waiting for my answer (newer than WAIT_TTL)
//   POST   /v1/waits/{id}/accept                          -> 204                "OK, waiting"; the sender is notified
//   GET    /v1/waits/{id}                                 -> 200 {status}       sender: pending | accepted | expired
//   PUT    /v1/live-activity         {"token","sandbox"}  -> 204                push token of my running Live Activity
//   DELETE /v1/live-activity                              -> 204
//   PUT    /v1/live                  {"riding":bool,"lat","lon","speedKmh"} -> 204   heartbeat; "riding" expires after 90 seconds.
//                                                                                Crew-mates see the latest position for 10 minutes after
//                                                                                the last heartbeat ("last seen"), or until riding:false
//   GET    /join/{code}                                   -> HTML invite page; /.well-known/ files make the link open the apps
//   PUT    /v1/push                  {"platform":"ios","token","sandbox":bool} -> 204  where to send "joined / left your crew"
//   DELETE /v1/push                  {"token"}            -> 204                stop notifying this device
//   GET    /v1/live/friends                               -> 200 [friend]       crew-mates riding right now
//
// friend = {"id","displayName","initials","sport","isRiding"} plus "lat","lon","speedKmh","ageSeconds" (seconds since the
// last heartbeat) while a recent position is known.
// crew = {"id","name","code","ownerId","myExpiresAt","meet","members":[friend + "joinedAt","expiresAt"]}; times are epoch
// seconds. meet = null or {"lat","lon","kind","at","byId","byName"}: one meet point per crew, gone MEET_GRACE after "at".
//
// seconds, null expiresAt means until they leave. Everything but registering needs "Authorization: Bearer <token>".
// Needs PHP 8.0+ with PDO MySQL. See schema.sql for what is stored.

declare(strict_types=1);

ini_set('serialize_precision', '-1'); // shortest exact float, so 42.69771 stays 42.69771 in JSON
header('Content-Type: application/json');
header('Cache-Control: no-store');

const MAX_CREW_MEMBERS = 3; // first release: free crews only; CREW+ (10) and CLUB (20) come with paid passes
const MAX_CREWS_PER_RIDER = 10;
const MAX_STAY_DAYS = 30; // the longest "until" a joiner can pick
const RIDING_TTL = 90;
const WAIT_TTL = 300; // seconds a "wait for me" stays open before it counts as unanswered
const POSITION_TTL = 600; // how long a friend can still see where you were last
const MEET_KINDS = ['lift', 'hut', 'parking', 'here'];
const MEET_GRACE = 1800; // a meet point stays this many seconds after its time, then disappears
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

function cleanSport(mixed $raw): string {
    if (!is_string($raw) || !in_array($raw, ['ski', 'board', 'bike', 'walk'], true)) {
        fail(400, 'Sport must be ski, board, bike or walk');
    }
    return $raw;
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
        'sport' => $row['sport'] ?? 'board',
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
    $rider = q('SELECT id, name, initials, sport, 0 AS riding FROM riders WHERE token_hash = ?', [hash('sha256', $m[1])])->fetch();
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

/** SQL condition: membership row `$m` has not expired. */
function active(string $m): string {
    return "($m.expires_at IS NULL OR $m.expires_at > UTC_TIMESTAMP())";
}

/** Everyone I share at least one active crew with. */
function crewMatesOf(string $me, bool $ridingOnly = false): array {
    $rows = q(
        'SELECT DISTINCT r.id, r.name, r.initials, r.sport, (r.riding_until > UTC_TIMESTAMP()) AS riding, r.lat, r.lon, r.speed,
                TIMESTAMPDIFF(SECOND, r.seen_at, UTC_TIMESTAMP()) AS age
           FROM crew_members mine
           JOIN crew_members other ON other.crew_id = mine.crew_id AND other.rider_id <> mine.rider_id
           JOIN riders r ON r.id = other.rider_id
          WHERE mine.rider_id = ? AND ' . active('mine') . ' AND ' . active('other') . '
          ORDER BY r.name',
        [$me]
    )->fetchAll();
    $friends = array_map('friendJson', $rows);
    return $ridingOnly ? array_values(array_filter($friends, fn ($f) => $f['isRiding'])) : $friends;
}

/** Whether two riders are in an active crew together right now. */
function sharesCrew(string $a, string $b): bool {
    return (bool)q(
        'SELECT 1 FROM crew_members x JOIN crew_members y ON y.crew_id = x.crew_id
          WHERE x.rider_id = ? AND y.rider_id = ? AND ' . active('x') . ' AND ' . active('y') . ' LIMIT 1',
        [$a, $b]
    )->fetchColumn();
}

/** Epoch seconds or null from a DATETIME column (stored in UTC). */
function epoch(?string $utc): ?int {
    return $utc === null ? null : (int)(new DateTimeImmutable($utc, new DateTimeZone('UTC')))->getTimestamp();
}

/** The "until" a joiner sent: null = until I leave; otherwise a UTC DATETIME at most MAX_STAY_DAYS ahead. */
function cleanUntil(mixed $raw): ?string {
    if ($raw === null) {
        return null;
    }
    if (!is_int($raw) && !(is_string($raw) && ctype_digit($raw))) {
        fail(400, 'until must be epoch seconds');
    }
    $until = (int)$raw;
    if ($until <= time()) {
        fail(400, 'until must be in the future');
    }
    return gmdate('Y-m-d H:i:s', min($until, time() + MAX_STAY_DAYS * 86400));
}

function cleanCrewName(mixed $raw): string {
    $name = is_string($raw) ? trim($raw) : '';
    $name = mb_substr($name, 0, 30);
    if ($name === '' || preg_match('/[\x00-\x1F\x7F]/', $name)) {
        fail(400, 'Crew name must be 1 to 30 characters');
    }
    return $name;
}

/** One crew as the app sees it, with its active members and their positions. */
function crewJson(array $crew, string $me): array {
    $rows = q(
        'SELECT r.id, r.name, r.initials, r.sport, (r.riding_until > UTC_TIMESTAMP()) AS riding, r.lat, r.lon, r.speed,
                TIMESTAMPDIFF(SECOND, r.seen_at, UTC_TIMESTAMP()) AS age, m.joined_at, m.expires_at
           FROM crew_members m JOIN riders r ON r.id = m.rider_id
          WHERE m.crew_id = ? AND ' . active('m') . '
          ORDER BY m.joined_at',
        [$crew['id']]
    )->fetchAll();
    $mine = null;
    $members = [];
    foreach ($rows as $row) {
        if ($row['id'] === $me) {
            $mine = epoch($row['expires_at']);
        }
        $members[] = friendJson($row) + ['joinedAt' => epoch($row['joined_at']), 'expiresAt' => epoch($row['expires_at'])];
    }
    return [
        'id' => $crew['id'],
        'name' => $crew['name'],
        'code' => $crew['code'],
        'ownerId' => $crew['owner_id'],
        'myExpiresAt' => $mine,
        'meet' => meetJson($crew),
        'members' => $members,
    ];
}

/** The crew's meet point, or null when there is none or its time is long past. */
function meetJson(array $crew): ?array {
    if (($crew['meet_lat'] ?? null) === null || ($crew['meet_at'] ?? null) === null) {
        return null;
    }
    $at = epoch($crew['meet_at']);
    if ($at === null || $at + MEET_GRACE < time()) {
        return null;
    }
    $by = q('SELECT name FROM riders WHERE id = ?', [$crew['meet_by']])->fetchColumn();
    return [
        'lat' => (float)$crew['meet_lat'],
        'lon' => (float)$crew['meet_lon'],
        'kind' => $crew['meet_kind'],
        'at' => $at,
        'byId' => $crew['meet_by'],
        'byName' => $by === false ? null : $by,
    ];
}

/** The crew `$id` if I am an active member of it, else a 404. */
function myCrew(string $id, string $me): array {
    $crew = q(
        'SELECT c.* FROM crews c JOIN crew_members m ON m.crew_id = c.id
          WHERE c.id = ? AND m.rider_id = ? AND ' . active('m'),
        [$id, $me]
    )->fetch();
    if (!$crew) {
        fail(404, 'No such crew');
    }
    return $crew;
}

/**
 * Removes memberships that ran out, then gives ownerless crews to their oldest member and deletes empty ones.
 * Visibility never depends on this (every query checks expires_at); it only keeps the tables small and tidy.
 */
function sweepCrews(): void {
    q('DELETE FROM crew_members WHERE expires_at IS NOT NULL AND expires_at <= UTC_TIMESTAMP()');
    q('DELETE FROM crews WHERE NOT EXISTS (SELECT 1 FROM crew_members m WHERE m.crew_id = crews.id)');
    q('UPDATE crews c SET owner_id = (SELECT m.rider_id FROM crew_members m WHERE m.crew_id = c.id ORDER BY m.joined_at LIMIT 1)
        WHERE NOT EXISTS (SELECT 1 FROM crew_members m WHERE m.crew_id = c.id AND m.rider_id = c.owner_id)');
}

/** Takes me out of one crew: the others are told, the crew passes on or goes when it is empty. */
function leaveCrew(array $crew, array $me): void {
    q('DELETE FROM crew_members WHERE crew_id = ? AND rider_id = ?', [$crew['id'], $me['id']]);
    $left = q('SELECT rider_id FROM crew_members WHERE crew_id = ? AND ' . active('crew_members'), [$crew['id']])->fetchAll(PDO::FETCH_COLUMN);
    foreach ($left as $other) {
        touchRiddenWith($me['id'], $other);
    }
    sweepCrews();
    if ($left) {
        afterResponse(function () use ($left, $me, $crew) {
            foreach ($left as $other) {
                notifyRider($other, 'leave', $me['name'], $crew['name']);
            }
        });
    }
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
        if (!q('SELECT 1 FROM crews WHERE code = ?', [$code])->fetchColumn()) {
            return $code;
        }
    }
    fail(500, 'Could not create a code');
}

// MARK: - Invite links shared in messengers

/** https://<host>/join/<code> opens the app when installed (universal link / app link), else this page. */
function invitePage(string $code): never {
    rateLimit('page', $_SERVER['REMOTE_ADDR'] ?? '', 120, 3600);
    $crew = q('SELECT c.name, r.name AS owner FROM crews c JOIN riders r ON r.id = c.owner_id WHERE c.code = ?', [strtoupper($code)])->fetch();
    $owner = $crew ? $crew['owner'] : null;
    $e = fn (string $text): string => htmlspecialchars($text, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    $code = strtoupper($code);
    $title = $crew ? $owner . ' invited you to ' . $crew['name'] : 'Ride together on RiDelirium';
    $text = $crew ? 'Join the crew to see each other on the map while you ride.' : 'This invite link is no longer valid. Ask for a new one.';
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
            . '<p style="margin-top:32px">No link button? In RiDelirium open Ride Together, tap JOIN CREW, then type the code:</p>'
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
        $in = body();
        $name = array_key_exists('name', $in) ? cleanName($in['name']) : $me['name'];
        $sport = array_key_exists('sport', $in) ? cleanSport($in['sport']) : $me['sport'];
        q('UPDATE riders SET name = ?, initials = ?, sport = ? WHERE id = ?', [$name, initials($name), $sport, $me['id']]);
        // Friends see the new tag now, not at their next poll.
        if ($name !== $me['name'] || $sport !== $me['sport']) {
            afterResponse(fn () => notifyCrewChanged($me['id'], 'profile'));
        }
        respond(200, friendJson(['id' => $me['id'], 'name' => $name, 'initials' => initials($name), 'sport' => $sport, 'riding' => 0]));
    }

    if ($route === '/riders/me' && $method === 'DELETE') {
        foreach (q('SELECT c.* FROM crews c JOIN crew_members m ON m.crew_id = c.id WHERE m.rider_id = ?', [$me['id']])->fetchAll() as $crew) {
            leaveCrew($crew, $me);
        }
        q('DELETE FROM riders WHERE id = ?', [$me['id']]); // memberships, waits and devices cascade
        respond(204);
    }

    if ($route === '/crews' && $method === 'GET') {
        sweepCrews();
        $crews = q(
            'SELECT c.* FROM crews c JOIN crew_members m ON m.crew_id = c.id
              WHERE m.rider_id = ? AND ' . active('m') . ' ORDER BY m.joined_at DESC',
            [$me['id']]
        )->fetchAll();
        respond(200, array_map(fn ($c) => crewJson($c, $me['id']), $crews));
    }

    if ($route === '/crews' && $method === 'POST') {
        rateLimit('crew', $me['id'], 20, 3600);
        $name = cleanCrewName(body()['name'] ?? null);
        $count = (int)q('SELECT COUNT(*) FROM crew_members m WHERE m.rider_id = ? AND ' . active('m'), [$me['id']])->fetchColumn();
        if ($count >= MAX_CREWS_PER_RIDER) {
            fail(409, 'Crew limit reached');
        }
        $id = bin2hex(random_bytes(8));
        q('INSERT INTO crews (id, name, code, owner_id, created_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP())', [$id, $name, newCode(), $me['id']]);
        q('INSERT INTO crew_members (crew_id, rider_id, joined_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(), NULL)', [$id, $me['id']]);
        respond(201, crewJson(myCrew($id, $me['id']), $me['id']));
    }

    if (preg_match('#^/crews/([0-9a-f]{16})$#', $route, $r) && ($method === 'PUT' || $method === 'PATCH')) {
        $crew = myCrew($r[1], $me['id']);
        if ($crew['owner_id'] !== $me['id']) {
            fail(403, 'Only the owner can rename the crew');
        }
        $name = cleanCrewName(body()['name'] ?? null);
        q('UPDATE crews SET name = ? WHERE id = ?', [$name, $crew['id']]);
        $crew['name'] = $name;
        afterResponse(fn () => notifyCrewChanged($me['id'], 'crew'));
        respond(200, crewJson($crew, $me['id']));
    }

    if (preg_match('#^/crews/preview/([A-Za-z0-9]{8})$#', $route, $r) && $method === 'GET') {
        rateLimit('join', $me['id'], 40, 3600);
        $crew = q('SELECT c.id, c.name, r.name AS owner FROM crews c JOIN riders r ON r.id = c.owner_id WHERE c.code = ?', [strtoupper($r[1])])->fetch();
        if (!$crew) {
            fail(404, 'Unknown code');
        }
        $members = (int)q('SELECT COUNT(*) FROM crew_members m WHERE m.crew_id = ? AND ' . active('m'), [$crew['id']])->fetchColumn();
        $already = (bool)q('SELECT 1 FROM crew_members m WHERE m.crew_id = ? AND m.rider_id = ? AND ' . active('m'), [$crew['id'], $me['id']])->fetchColumn();
        respond(200, ['id' => $crew['id'], 'name' => $crew['name'], 'ownerName' => $crew['owner'], 'memberCount' => $members, 'isMember' => $already]);
    }

    if ($route === '/crews/join' && $method === 'POST') {
        // Codes are a shared secret; keep guessing slow.
        rateLimit('join', $me['id'], 20, 3600);
        $in = body();
        $code = is_string($in['code'] ?? null) ? strtoupper($in['code']) : '';
        $until = cleanUntil($in['until'] ?? null);
        $crew = preg_match('/^[A-Z0-9]{8}$/', $code) ? q('SELECT * FROM crews WHERE code = ?', [$code])->fetch() : false;
        if (!$crew) {
            fail(404, 'Unknown code');
        }
        sweepCrews();
        $member = q('SELECT 1 FROM crew_members WHERE crew_id = ? AND rider_id = ?', [$crew['id'], $me['id']])->fetchColumn();
        if ($member) {
            // Already in: joining again only changes how long I stay.
            q('UPDATE crew_members SET expires_at = ? WHERE crew_id = ? AND rider_id = ?', [$until, $crew['id'], $me['id']]);
            respond(200, crewJson($crew, $me['id']));
        }
        $mine = (int)q('SELECT COUNT(*) FROM crew_members m WHERE m.rider_id = ? AND ' . active('m'), [$me['id']])->fetchColumn();
        if ($mine >= MAX_CREWS_PER_RIDER) {
            fail(409, 'Crew limit reached');
        }
        $others = q('SELECT rider_id FROM crew_members m WHERE m.crew_id = ? AND ' . active('m'), [$crew['id']])->fetchAll(PDO::FETCH_COLUMN);
        if (count($others) >= MAX_CREW_MEMBERS) {
            fail(409, 'This crew is full');
        }
        q('INSERT INTO crew_members (crew_id, rider_id, joined_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(), ?)', [$crew['id'], $me['id'], $until]);
        foreach ($others as $other) {
            touchRiddenWith($me['id'], $other);
        }
        if ($others) {
            afterResponse(function () use ($others, $me, $crew) {
                foreach ($others as $other) {
                    notifyRider($other, 'join', $me['name'], $crew['name']);
                }
            });
        }
        respond(200, crewJson($crew, $me['id']));
    }

    if (preg_match('#^/crews/([0-9a-f]{16})/membership$#', $route, $r) && $method === 'PUT') {
        $crew = myCrew($r[1], $me['id']);
        $until = cleanUntil(body()['until'] ?? null);
        q('UPDATE crew_members SET expires_at = ? WHERE crew_id = ? AND rider_id = ?', [$until, $crew['id'], $me['id']]);
        respond(200, crewJson($crew, $me['id']));
    }

    if (preg_match('#^/crews/([0-9a-f]{16})/membership$#', $route, $r) && $method === 'DELETE') {
        $crew = q('SELECT * FROM crews WHERE id = ?', [$r[1]])->fetch();
        if ($crew) {
            leaveCrew($crew, $me);
        }
        respond(204);
    }

    // One meet point per crew: dropping a new one replaces the old; everyone else is told.
    if (preg_match('#^/crews/([0-9a-f]{16})/meet$#', $route, $r) && $method === 'PUT') {
        $crew = myCrew($r[1], $me['id']);
        rateLimit('meet', $me['id'], 20, 3600);
        $in = body();
        $lat = $in['lat'] ?? null;
        $lon = $in['lon'] ?? null;
        $kind = $in['kind'] ?? null;
        $minutes = $in['inMinutes'] ?? 0;
        if (!is_numeric($lat) || !is_numeric($lon) || abs((float)$lat) > 90 || abs((float)$lon) > 180) {
            fail(400, 'lat and lon are required');
        }
        if (!is_string($kind) || !in_array($kind, MEET_KINDS, true)) {
            fail(400, 'kind must be lift, hut, parking or here');
        }
        if (!is_numeric($minutes) || (int)$minutes < 0 || (int)$minutes > 120) {
            fail(400, 'inMinutes must be 0 to 120');
        }
        $minutes = (int)$minutes;
        q('UPDATE crews SET meet_lat = ?, meet_lon = ?, meet_kind = ?, meet_at = UTC_TIMESTAMP() + INTERVAL ? MINUTE, meet_by = ? WHERE id = ?',
            [(float)$lat, (float)$lon, $kind, $minutes, $me['id'], $crew['id']]);
        $crew = q('SELECT * FROM crews WHERE id = ?', [$crew['id']])->fetch();
        $others = q('SELECT rider_id FROM crew_members m WHERE m.crew_id = ? AND m.rider_id <> ? AND ' . active('m'), [$crew['id'], $me['id']])->fetchAll(PDO::FETCH_COLUMN);
        if ($others) {
            afterResponse(function () use ($others, $me, $crew, $kind, $minutes) {
                foreach ($others as $other) {
                    notifyMeet($other, $me['name'], $kind, $minutes, $crew['name'], $crew['id']);
                }
            });
        }
        respond(200, crewJson($crew, $me['id']));
    }

    if (preg_match('#^/crews/([0-9a-f]{16})/meet$#', $route, $r) && $method === 'DELETE') {
        $crew = myCrew($r[1], $me['id']);
        q('UPDATE crews SET meet_lat = NULL, meet_lon = NULL, meet_kind = NULL, meet_at = NULL, meet_by = NULL WHERE id = ?', [$crew['id']]);
        $crew = q('SELECT * FROM crews WHERE id = ?', [$crew['id']])->fetch();
        afterResponse(fn () => notifyCrewChanged($me['id'], 'meet'));
        respond(200, crewJson($crew, $me['id']));
    }

    // People I rode with in the last 30 days who are not in my crew now.
    if ($route === '/riders/recent' && $method === 'GET') {
        $rows = q(
            'SELECT r.id, r.name, r.initials, r.sport
               FROM ridden_with w
               JOIN riders r ON r.id = IF(w.rider_a = ?, w.rider_b, w.rider_a)
              WHERE (w.rider_a = ? OR w.rider_b = ?) AND w.last_at > UTC_TIMESTAMP() - INTERVAL 30 DAY
                AND NOT EXISTS (SELECT 1 FROM crew_members x JOIN crew_members y ON y.crew_id = x.crew_id
                                 WHERE x.rider_id = w.rider_a AND y.rider_id = w.rider_b AND ' . active('x') . ' AND ' . active('y') . ')
              ORDER BY w.last_at DESC LIMIT 20',
            [$me['id'], $me['id'], $me['id']]
        )->fetchAll();
        respond(200, array_map(fn ($r) => ['id' => $r['id'], 'displayName' => $r['name'], 'initials' => $r['initials'], 'sport' => $r['sport']], $rows));
    }

    // Invite a recent rider into one of my crews: a push with its code. Only people I rode with lately can be asked.
    if (preg_match('#^/riders/([0-9A-Fa-f-]{36})/invite$#', $route, $r) && $method === 'POST') {
        $other = strtoupper($r[1]);
        [$a, $b] = $me['id'] < $other ? [$me['id'], $other] : [$other, $me['id']];
        $recent = q('SELECT 1 FROM ridden_with WHERE rider_a = ? AND rider_b = ? AND last_at > UTC_TIMESTAMP() - INTERVAL 30 DAY', [$a, $b])->fetchColumn();
        if (!$recent) {
            fail(404, 'Not a recent rider');
        }
        $crewId = body()['crewId'] ?? '';
        $crew = myCrew(is_string($crewId) ? $crewId : '', $me['id']);
        rateLimit('invite', $me['id'] . '|' . $other, 3, 3600);
        afterResponse(fn () => notifyInvite($other, $me['name'], $crew['code'], $crew['name']));
        respond(204);
    }

    if ($route === '/friends' && $method === 'GET') {
        respond(200, crewMatesOf($me['id']));
    }

    if (preg_match('#^/friends/([0-9A-Fa-f-]{36})/wait$#', $route, $r) && $method === 'POST') {
        $other = strtoupper($r[1]);
        if (!sharesCrew($me['id'], $other)) {
            fail(404, 'Not in a crew with you');
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
        respond(200, crewMatesOf($me['id'], ridingOnly: true));
    }

    fail(404, 'Not found');
} catch (PDOException $e) {
    error_log('Ride Together API: ' . $e->getMessage());
    fail(500, 'Server error');
}

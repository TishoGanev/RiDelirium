<?php
// Sends test pushes to a rider's phones and shows what is registered and exactly what Apple answers.
//   docker exec ridersapi php pushtest.php "Tihomir"                 plain alert (iOS via APNs, Android via FCM)
//   docker exec ridersapi php pushtest.php "Tihomir" wait            the "WAIT FOR ME" alert (+ Live Activity update if one is registered)
//   docker exec ridersapi php pushtest.php "Tihomir" activity        only the Live Activity update

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

$config = file_exists(__DIR__ . '/../config.php') ? require __DIR__ . '/../config.php' : [];
function cfg(string $key): ?string {
    global $config;
    $value = $config[$key] ?? getenv($key);
    return $value === false || $value === '' ? null : (string)$value;
}
function q(string $sql, array $params = []): PDOStatement {
    static $pdo = null;
    $pdo ??= new PDO(cfg('DB_DSN'), cfg('DB_USER'), cfg('DB_PASS'), [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    return $stmt;
}
const WAIT_TTL = 300;
require __DIR__ . '/push.php';

function report(string $label, bool $ok, bool $android = false): void {
    $a = $GLOBALS['apnsLast'] ?? null;
    if ($android) {
        echo "  $label -> " . ($ok ? 'sent to FCM (no error logged is good; failures show in docker logs)' : 'REJECTED, token is dead') . "\n";
        return;
    }
    echo "  $label -> " . ($ok ? 'accepted' : 'REJECTED');
    if ($a) {
        echo " [HTTP {$a['status']}" . ($a['reason'] ? " {$a['reason']}" : '') . ($a['curl'] ? " curl: {$a['curl']}" : '') . " via {$a['host']}]";
    } else {
        echo ' [nothing sent: APNs not configured]';
    }
    echo "\n";
    $GLOBALS['apnsLast'] = null;
}

foreach (['APNS_KEY_ID', 'APNS_TEAM_ID', 'APNS_TOPIC'] as $key) {
    echo "$key: " . (cfg($key) ?? 'MISSING') . "\n";
}
$keyFile = cfg('APNS_KEY_FILE');
echo 'key file: ' . ($keyFile && is_file($keyFile) ? 'ok' : 'MISSING or not a file (' . $keyFile . ')') . "\n";
echo 'HTTP/2 in curl: ' . (defined('CURL_HTTP_VERSION_2_0') && (curl_version()['features'] & CURL_VERSION_HTTP2) ? 'yes' : 'NO') . "\n\n";

$fcmFile = cfg('FCM_KEY_FILE');
echo 'FCM_PROJECT_ID: ' . (cfg('FCM_PROJECT_ID') ?? 'MISSING') . "\n";
echo 'FCM key file: ' . ($fcmFile && is_file($fcmFile) && filesize($fcmFile) > 10 ? 'ok' : 'MISSING or empty (' . $fcmFile . ')') . "\n";
echo "Android: 'accepted' means FCM took it; a rejection is written to the log (docker logs ridersapi).\n\n";

$needle = $argv[1] ?? '';
$mode = $argv[2] ?? 'alert';
$riders = q('SELECT id, name, initials FROM riders WHERE name LIKE ?', ['%' . $needle . '%'])->fetchAll();
foreach ($riders as $rider) {
    $devices = q('SELECT token, platform, sandbox FROM devices WHERE rider_id = ?', [$rider['id']])->fetchAll();
    $activity = q('SELECT token, sandbox, updated_at FROM live_activities WHERE rider_id = ?', [$rider['id']])->fetch();
    echo "{$rider['name']} ({$rider['id']}): " . count($devices) . " device(s), live activity token: " . ($activity ? 'yes (' . $activity['updated_at'] . ' UTC, sandbox=' . $activity['sandbox'] . ')' : 'NONE') . "\n";

    if ($mode !== 'activity') {
        $body = $mode === 'wait' ? 'TEST: WAIT FOR ME · 340 M' : 'Test notification';
        foreach ($devices as $d) {
            $payload = ['aps' => ['alert' => ['title' => 'RideLirium', 'body' => $body], 'sound' => 'default', 'thread-id' => 'wait', 'interruption-level' => 'time-sensitive'], 'event' => $mode === 'wait' ? 'wait' : 'test'];
            $ok = deliver($d, $payload);
            report("alert {$d['platform']} token=" . substr($d['token'], 0, 8) . '…', $ok, $d['platform'] !== 'ios');
        }
    }
    if ($mode !== 'alert' && $activity) {
        $wait = ['id' => 'test0000test0000', 'from_rider' => $rider['id'], 'name' => 'Test', 'initials' => 'TE', 'distance_m' => 340];
        $payload = ['aps' => [
            'timestamp' => time(), 'event' => 'update',
            'content-state' => waitContentState($wait, 'request', 1),
            'stale-date' => time() + WAIT_TTL,
            'alert' => ['title' => 'RideLirium', 'body' => 'TEST: WAIT FOR ME · 340 M'], 'sound' => 'default',
        ]];
        report('live activity update', sendApns($activity['token'], (bool)$activity['sandbox'], $payload, 'liveactivity', '.push-type.liveactivity'));
    } elseif ($mode !== 'alert') {
        echo "  live activity: no token registered, so nothing to update (start a ride with the app open first)\n";
    }
}
if (!$riders) {
    echo "No rider with a name like '$needle'.\n";
}

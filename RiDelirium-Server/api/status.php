<?php
// Shows what is in the database: riders, crews and their members, and push devices.
//   docker exec ridersapi php status.php
// Command line only. Token hashes and push tokens are never printed in full.

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

$config = file_exists(__DIR__ . '/../config.php') ? require __DIR__ . '/../config.php' : [];
$pdo = new PDO($config['DB_DSN'] ?? getenv('DB_DSN'), $config['DB_USER'] ?? getenv('DB_USER') ?: null, $config['DB_PASS'] ?? getenv('DB_PASS') ?: null, [
    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
]);
$pdo->exec("SET time_zone = '+00:00'");

function table(string $title, array $rows): void {
    echo "\n== $title (" . count($rows) . ") ==\n";
    if (!$rows) {
        echo "  none\n";
        return;
    }
    $headers = array_keys($rows[0]);
    $widths = [];
    foreach ($headers as $h) {
        $widths[$h] = max(mb_strlen((string)$h), ...array_map(fn ($r) => mb_strlen((string)($r[$h] ?? '')), $rows));
    }
    $line = fn (array $cells) => '  ' . implode('  ', array_map(
        fn ($h) => $cells[$h] . str_repeat(' ', $widths[$h] - mb_strlen((string)$cells[$h])), $headers
    )) . "\n";
    echo $line(array_combine($headers, $headers));
    foreach ($rows as $row) {
        echo $line(array_map(fn ($v) => $v === null ? '-' : (string)$v, $row));
    }
}

$short = fn (string $id): string => substr($id, 0, 8);

$riders = $pdo->query(
    'SELECT id, name, (riding_until > UTC_TIMESTAMP()) AS riding, lat, lon, speed,
            TIMESTAMPDIFF(SECOND, seen_at, UTC_TIMESTAMP()) AS pos_age_s, last_seen,
            (SELECT COUNT(*) FROM devices d WHERE d.rider_id = riders.id) AS devices
       FROM riders ORDER BY last_seen DESC, name'
)->fetchAll();
$names = array_column($riders, 'name', 'id');

table('Riders', array_map(fn ($r) => [
    'id' => $short($r['id']),
    'name' => $r['name'],
    'riding' => $r['riding'] ? 'yes' : 'no',
    'position' => $r['lat'] !== null ? "{$r['lat']}, {$r['lon']}" : null,
    'km/h' => $r['speed'],
    'pos age s' => $r['pos_age_s'],
    'last seen' => $r['last_seen'],
    'push devices' => $r['devices'],
], $riders));

$members = $pdo->query(
    'SELECT m.crew_id, m.rider_id, m.joined_at, m.expires_at, (m.expires_at IS NOT NULL AND m.expires_at <= UTC_TIMESTAMP()) AS expired
       FROM crew_members m ORDER BY m.crew_id, m.joined_at'
)->fetchAll();
$byCrew = [];
foreach ($members as $m) {
    $byCrew[$m['crew_id']][] = $m;
}

table('Crews', array_map(fn ($c) => [
    'id' => $c['id'],
    'name' => $c['name'],
    'code' => $c['code'],
    'owner' => $names[$c['owner_id']] ?? $short($c['owner_id']),
    'members' => count(array_filter($byCrew[$c['id']] ?? [], fn ($m) => !$m['expired'])),
    'created' => $c['created_at'],
], $pdo->query('SELECT id, name, code, owner_id, created_at FROM crews ORDER BY created_at DESC')->fetchAll()));

table('Crew members', array_map(fn ($m) => [
    'crew' => $m['crew_id'],
    'rider' => ($names[$m['rider_id']] ?? '?') . ' (' . $short($m['rider_id']) . ')',
    'joined' => $m['joined_at'],
    'until' => $m['expires_at'] ?? 'leaves',
    'expired' => $m['expired'] ? 'yes' : 'no',
], $members));

$inCrew = array_flip(array_column($members, 'rider_id'));
table('Riders in no crew', array_map(fn ($r) => ['id' => $short($r['id']), 'name' => $r['name']],
    array_values(array_filter($riders, fn ($r) => !isset($inCrew[$r['id']])))));

table('Push devices', array_map(fn ($d) => [
    'rider' => $names[$d['rider_id']] ?? '?', 'platform' => $d['platform'],
    'sandbox' => $d['sandbox'] ? 'yes' : 'no', 'token' => substr($d['token'], 0, 8) . '…',
], $pdo->query('SELECT token, rider_id, platform, sandbox FROM devices ORDER BY rider_id')->fetchAll()));

$limits = $pdo->query('SELECT COUNT(*) AS rows_, COALESCE(SUM(n), 0) AS requests FROM rate_limits')->fetch();
echo "\nRate-limit rows: {$limits['rows_']}, requests counted: {$limits['requests']}\n";

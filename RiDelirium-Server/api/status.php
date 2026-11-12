<?php
// Shows what is in the database: riders, crews (groups of friends), friendships, invites and push devices.
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

$pairs = $pdo->query('SELECT rider_a, rider_b FROM friendships')->fetchAll();

// A crew is everyone connected through friendships, found with a small union-find.
$parent = [];
$find = function (string $x) use (&$parent, &$find): string {
    $parent[$x] ??= $x;
    return $parent[$x] === $x ? $x : ($parent[$x] = $find($parent[$x]));
};
foreach ($pairs as $p) {
    $parent[$find($p['rider_a'])] = $find($p['rider_b']);
}
$crews = [];
foreach (array_keys($parent) as $id) {
    $crews[$find($id)][] = $names[$id] ?? $short($id);
}
$crewRows = [];
foreach (array_values($crews) as $i => $members) {
    sort($members);
    $crewRows[] = ['crew' => $i + 1, 'size' => count($members), 'members' => implode(', ', $members)];
}
table('Crews (everyone connected by friendships)', $crewRows);

table('Friendships', array_map(fn ($p) => [
    'rider' => ($names[$p['rider_a']] ?? '?') . ' (' . $short($p['rider_a']) . ')',
    'friend' => ($names[$p['rider_b']] ?? '?') . ' (' . $short($p['rider_b']) . ')',
], $pairs));

$alone = array_filter($riders, fn ($r) => !isset($parent[$r['id']]));
table('Riders in no crew', array_map(fn ($r) => ['id' => $short($r['id']), 'name' => $r['name']], array_values($alone)));

table('Invites', array_map(fn ($i) => ['code' => $i['code'], 'owner' => ($names[$i['rider_id']] ?? '?')],
    $pdo->query('SELECT code, rider_id FROM invites ORDER BY code')->fetchAll()));

table('Push devices', array_map(fn ($d) => [
    'rider' => $names[$d['rider_id']] ?? '?', 'platform' => $d['platform'],
    'sandbox' => $d['sandbox'] ? 'yes' : 'no', 'token' => substr($d['token'], 0, 8) . '…',
], $pdo->query('SELECT token, rider_id, platform, sandbox FROM devices ORDER BY rider_id')->fetchAll()));

$limits = $pdo->query('SELECT COUNT(*) AS rows_, COALESCE(SUM(n), 0) AS requests FROM rate_limits')->fetch();
echo "\nRate-limit rows: {$limits['rows_']}, requests counted: {$limits['requests']}\n";

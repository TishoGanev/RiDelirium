<?php
// Run daily from cron: php api/purge.php
// Deletes riders not seen for 30 days (their memberships cascade), memberships that ran out, empty crews and old rate-limit rows.

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

$config = file_exists(__DIR__ . '/../config.php') ? require __DIR__ . '/../config.php' : [];
$pdo = new PDO($config['DB_DSN'] ?? getenv('DB_DSN'), $config['DB_USER'] ?? getenv('DB_USER') ?: null, $config['DB_PASS'] ?? getenv('DB_PASS') ?: null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$pdo->exec("SET time_zone = '+00:00'");

$riders = $pdo->exec('DELETE FROM riders WHERE last_seen < UTC_DATE() - INTERVAL 30 DAY');
$waits = $pdo->exec('DELETE FROM waits WHERE created_at < UTC_TIMESTAMP() - INTERVAL 1 DAY');
$ridden = $pdo->exec('DELETE FROM ridden_with WHERE last_at < UTC_TIMESTAMP() - INTERVAL 30 DAY');
$members = $pdo->exec('DELETE FROM crew_members WHERE expires_at IS NOT NULL AND expires_at <= UTC_TIMESTAMP()');
$crews = $pdo->exec('DELETE FROM crews WHERE NOT EXISTS (SELECT 1 FROM crew_members m WHERE m.crew_id = crews.id)');
// A crew whose owner left or was purged passes to its oldest member.
$pdo->exec('UPDATE crews c SET owner_id = (SELECT m.rider_id FROM crew_members m WHERE m.crew_id = c.id ORDER BY m.joined_at LIMIT 1)
             WHERE NOT EXISTS (SELECT 1 FROM crew_members m WHERE m.crew_id = c.id AND m.rider_id = c.owner_id)');
$limits = $pdo->exec('DELETE FROM rate_limits WHERE bucket < UNIX_TIMESTAMP() DIV 3600 - 24');
echo "purged $riders riders, $members expired memberships, $crews empty crews, $waits waits, $ridden old crew pairs, $limits rate-limit rows\n";

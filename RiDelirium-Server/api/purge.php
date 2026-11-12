<?php
// Run daily from cron: php api/purge.php
// Deletes riders not seen for 30 days (their invites and friendships cascade) and old rate-limit rows.

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
$limits = $pdo->exec('DELETE FROM rate_limits WHERE bucket < UNIX_TIMESTAMP() DIV 3600 - 24');
echo "purged $riders riders, $waits waits, $ridden old crew pairs, $limits rate-limit rows\n";

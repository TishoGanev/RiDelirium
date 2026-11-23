<?php
// Runs every minute from the "sweeper" service in docker-compose.yml: php sweep.php
// Ends memberships whose time ran out (the rest of the crew is told, as when someone leaves), passes on or
// deletes crews left without an owner or members, and deletes positions nobody refreshed for ten minutes.

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

define('RIDE_TOGETHER_LIB', true);
require __DIR__ . '/index.php';

try {
    sweepCrews();
    clearStalePositions();
} catch (Throwable $e) {
    error_log('Ride Together sweep: ' . $e->getMessage());
    exit(1);
}

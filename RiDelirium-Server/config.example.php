<?php
// Copy to config.php (keep it out of git) and fill in your keys.
return [
    // Voice (token.php)
    'LIVEKIT_API_KEY' => 'APIxxxxxxxxxxxx',
    'LIVEKIT_API_SECRET' => 'your-secret',

    // Ride Together API (api/)
    'DB_DSN' => 'mysql:host=localhost;dbname=snowboarder;charset=utf8mb4',
    'DB_USER' => 'snowboarder',
    'DB_PASS' => 'your-password',
    'RATE_SALT' => 'long-random-string', // salts the hashed keys used for rate limiting
];

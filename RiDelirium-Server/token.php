<?php
// Mints a LiveKit access token so a rider can join their group's voice room.
//
//   POST /token.php   {"room": "ABCD2345", "identity": "<stable rider id>", "name": "Alex"}
//   -> 200 {"token": "<jwt>"}
//
// Configure with environment variables (or a config.php that returns the same array):
//   LIVEKIT_API_KEY, LIVEKIT_API_SECRET   from your LiveKit project (cloud or self-hosted)
//
// The apps then connect to your LiveKit URL (wss://<project>.livekit.cloud) with this token.
// Needs PHP 7.4+, no libraries.

header('Content-Type: application/json');
header('Cache-Control: no-store');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    exit(json_encode(['error' => 'POST only']));
}

$config = file_exists(__DIR__ . '/config.php') ? require __DIR__ . '/config.php' : [];
$apiKey = $config['LIVEKIT_API_KEY'] ?? getenv('LIVEKIT_API_KEY');
$apiSecret = $config['LIVEKIT_API_SECRET'] ?? getenv('LIVEKIT_API_SECRET');
if (!$apiKey || !$apiSecret) {
    http_response_code(500);
    exit(json_encode(['error' => 'Server is not configured']));
}

$body = json_decode(file_get_contents('php://input'), true);
$room = strtoupper(trim($body['room'] ?? ''));
$identity = trim($body['identity'] ?? '');
$name = trim($body['name'] ?? 'Rider');

// Room names are the 8-character Ride Together codes (letters and digits).
if (!preg_match('/^[A-Z0-9]{8}$/', $room) || $identity === '' || strlen($identity) > 64) {
    http_response_code(400);
    exit(json_encode(['error' => 'Invalid request']));
}
$name = mb_substr($name === '' ? 'Rider' : $name, 0, 40);

function b64url(string $data): string {
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

$now = time();
$header = ['alg' => 'HS256', 'typ' => 'JWT'];
$claims = [
    'iss' => $apiKey,
    'sub' => $identity,
    'name' => $name,
    'nbf' => $now,
    'iat' => $now,
    'exp' => $now + 6 * 3600, // a day on the mountain
    'video' => [
        'room' => 'sb-' . $room,
        'roomJoin' => true,
        'canPublish' => true,
        'canSubscribe' => true,
        'canPublishData' => false,
    ],
];

$signing = b64url(json_encode($header)) . '.' . b64url(json_encode($claims));
$signature = b64url(hash_hmac('sha256', $signing, $apiSecret, true));

echo json_encode(['token' => $signing . '.' . $signature]);

#!/bin/sh
# Copies the API to the NAS and rebuilds its container.
#   ./deploy.sh
#   NAS=user@host ./deploy.sh
#   SYNC_ENV=1 ./deploy.sh     also replace the NAS's .env (and apns.p8, fcm.json) with your local ones
# Sends Dockerfile, docker-compose.yml and api/. Never overwrites the NAS's .env or apns.p8:
# a missing or empty .env, apns.p8 or fcm.json is copied once.
# One SSH connection is shared by all steps, so the password is asked once.
# If creds.txt (gitignored, holds only the NAS password) exists, the password is read from it instead.
set -eu
cd "$(dirname "$0")"

NAS="${NAS:-tahatunga@10.10.10.18}"
DIR="${DIR:-/share/Container/ridersapi}"
SOCKET="${TMPDIR:-/tmp}/ridersapi-deploy-$$"
ASKPASS="${TMPDIR:-/tmp}/ridersapi-askpass-$$"
CREDS="$PWD/creds.txt"
nas() { ssh -o ControlMaster=auto -o ControlPath="$SOCKET" -o ControlPersist=120 "$NAS" "$@"; }
trap 'ssh -o ControlPath="$SOCKET" -O exit "$NAS" 2>/dev/null || true; rm -f "$ASKPASS"' EXIT

if [ -s "$CREDS" ]; then
    printf '#!/bin/sh\ncat "%s"\n' "$CREDS" > "$ASKPASS"
    chmod 700 "$ASKPASS"
    export SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}"
fi
# A script run over ssh has no login PATH, so docker is not found; QTS keeps it with Container Station.
# QTS also keeps docker's config under a folder the SSH user can't create.
REMOTE_ENV="export PATH=\$PATH:/share/CACHEDEV1_DATA/.qpkg/container-station/bin:/usr/local/bin:/usr/bin; export DOCKER_CONFIG=/share/Container/.docker; mkdir -p \$DOCKER_CONFIG; cd $DIR"

for f in Dockerfile docker-compose.yml api/index.php; do
    [ -f "$f" ] || { echo "Run from the Server folder: missing $f" >&2; exit 1; }
done
for f in api/*.php; do php -l "$f" >/dev/null; done

echo "Copying to $NAS:$DIR"
COPYFILE_DISABLE=1 tar --no-xattrs --exclude .DS_Store --exclude '*.md' --exclude '*.sql' --exclude '*.p8' --exclude 'fcm.json' \
    -cf - Dockerfile docker-compose.yml .dockerignore api \
    | nas "mkdir -p $DIR && cd $DIR && tar -xf - 2>/dev/null"

# The QNAP's SSH has no scp/sftp, so secrets are streamed through the shell instead.
for secret in .env apns.p8 fcm.json; do
    if [ -f "$secret" ] && { [ "${SYNC_ENV:-}" = 1 ] || nas "[ ! -s $DIR/$secret ]"; }; then
        echo "Copying $secret to the NAS"
        # Readable inside the container (Apache runs as www-data); the NAS share itself is private.
        nas "cat > $DIR/$secret && chmod 644 $DIR/$secret" < "$secret"
    fi
done

echo "Building and starting"
nas "$REMOTE_ENV; [ -f apns.p8 ] || echo 'Note: no apns.p8 here, iOS push stays off.'; [ -s fcm.json ] || { touch fcm.json; echo 'Note: no fcm.json here, Android push stays off.'; }; docker compose up -d --build"

echo "Checking"
sleep 3
nas "$REMOTE_ENV; docker exec ridersapi curl -s -o /dev/null -w 'API answered HTTP %{http_code} (401 is right)\n' http://localhost/v1/friends; docker logs --tail 5 ridersapi 2>&1"

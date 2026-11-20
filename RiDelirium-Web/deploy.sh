#!/bin/sh
# Copies the website to the NAS and (re)starts its nginx container.
#   ./deploy.sh
#   NAS=user@host ./deploy.sh
# NAS layout: /share/Container/rideliriumweb/ holds docker-compose.yml, site.conf and html/.
# The NAS password is read from ../RiDelirium-Server/creds.txt (or CREDS=path) when it exists.
set -eu
cd "$(dirname "$0")"

NAS="${NAS:-tahatunga@10.10.10.18}"
DIR="${DIR:-/share/Container/rideliriumweb}"
SOCKET="${TMPDIR:-/tmp}/rideliriumweb-deploy-$$"
ASKPASS="${TMPDIR:-/tmp}/rideliriumweb-askpass-$$"
CREDS="${CREDS:-$PWD/../RiDelirium-Server/creds.txt}"
nas() { ssh -o ControlMaster=auto -o ControlPath="$SOCKET" -o ControlPersist=120 "$NAS" "$@"; }
trap 'ssh -o ControlPath="$SOCKET" -O exit "$NAS" 2>/dev/null || true; rm -f "$ASKPASS"' EXIT

if [ -s "$CREDS" ]; then
    printf '#!/bin/sh\ncat "%s"\n' "$CREDS" > "$ASKPASS"
    chmod 700 "$ASKPASS"
    export SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}"
fi

REMOTE_ENV="export PATH=\$PATH:/share/CACHEDEV1_DATA/.qpkg/container-station/bin:/usr/local/bin:/usr/bin; export DOCKER_CONFIG=/share/Container/.docker; mkdir -p \$DOCKER_CONFIG; cd $DIR"

[ -f index.html ] || { echo "Missing index.html" >&2; exit 1; }

echo "Copying config to $NAS:$DIR"
COPYFILE_DISABLE=1 tar --no-xattrs -C deploy -cf - docker-compose.yml site.conf \
    | nas "mkdir -p $DIR/html && cd $DIR && tar -xf - 2>/dev/null"

echo "Copying site"
COPYFILE_DISABLE=1 tar --no-xattrs --exclude .DS_Store -cf - *.html css js assets \
    | nas "cd $DIR/html && rm -rf ./* && tar -xf - 2>/dev/null && chmod -R a+rX ."

echo "Starting"
nas "$REMOTE_ENV; docker compose up -d && docker exec rideliriumweb nginx -s reload"

echo "Checking"
sleep 2
nas "$REMOTE_ENV; for p in / /privacy /support /css/style.css; do docker exec rideliriumweb wget -q -O /dev/null -S http://127.0.0.1\$p 2>&1 | head -1 | sed \"s|^|\$p |\"; done"

#!/bin/sh
# Starts the server as the unprivileged `vectra` user.
#
# The container starts as root only for this script: it re-owns the data
# directory (a volume created by the old image, which ran as root, is
# root-owned, and so is anything written by a root `docker exec`), then drops
# to `vectra` with su-exec before the server starts. Run by hand as any other
# user, it starts the server directly.
set -eu

DATA_DIR=/app/pb_data
APP_USER=vectra

if [ "$(id -u)" = "0" ]; then
	mkdir -p "$DATA_DIR"
	# pb_data holds a handful of files (SQLite database, WAL, backups), so a
	# full re-own on every start is cheap; -h never follows a symlink out.
	chown -R -h "$APP_USER:$APP_USER" "$DATA_DIR"
	exec su-exec "$APP_USER:$APP_USER" /app/pb "$@"
fi

exec /app/pb "$@"

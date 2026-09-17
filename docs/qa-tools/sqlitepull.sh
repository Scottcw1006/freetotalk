#!/bin/zsh
# Copy an app's SQLite database (main file + -wal + -shm, whichever exist) off the device
# for read-only inspection on the host. The device files are never written.
# usage: sqlitepull.sh PKG DB_RELATIVE_PATH OUTDIR      e.g. sqlitepull.sh com.x.y databases/app.db /tmp/out
#        sqlitepull.sh selftest PKG DB_RELATIVE_PATH   -> prints "selftest OK: N tables" if the copy opens
# Stop the app first (am force-stop) if you need a consistent snapshot.
set -e
if [ "$1" = selftest ]; then
  T=$(mktemp -d); "$0" "$2" "$3" "$T" >/dev/null
  n=$(sqlite3 "$T/$(basename $3)" "select count(*) from sqlite_master where type='table'")
  rm -rf "$T"; echo "selftest OK: $n tables"; exit 0
fi
PKG=$1; DB=$2; OUT=$3; mkdir -p "$OUT"
dir=$(dirname "$DB"); base=$(basename "$DB")
present=$(adb exec-out run-as "$PKG" ls -1 "$dir" < /dev/null | tr -d '\r')
for suf in "" "-wal" "-shm"; do
  if echo "$present" | grep -qx "$base$suf"; then
    adb exec-out run-as "$PKG" cat "$DB$suf" < /dev/null > "$OUT/$base$suf"
    echo "copied $base$suf $(wc -c < "$OUT/$base$suf") bytes"
  fi
done

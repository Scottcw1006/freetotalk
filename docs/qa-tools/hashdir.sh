#!/bin/zsh
# Hash every regular file under a directory inside a debuggable app's private storage.
# Product-agnostic: package and directory are arguments.
#
# usage:
#   hashdir.sh PKG DIR            print "sha256  relative/path" per file, sorted
#   hashdir.sh PKG DIR > a.txt; ...; hashdir.sh PKG DIR > b.txt; diff a.txt b.txt
#   hashdir.sh selftest PKG       check run-as works and that the output line count
#                                 equals the device-side file count (0-line output
#                                 silently breaks any before/after comparison)
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

if [[ "${1:-}" == "selftest" ]]; then
  PKG="$2"
  n_files=$(adb exec-out run-as "$PKG" sh -c 'find files -type f | wc -l' < /dev/null | tr -d '\r ')
  n_lines=$("$0" "$PKG" files | wc -l | tr -d ' ')
  if [[ "$n_files" == "$n_lines" && "$n_files" != "0" ]]; then
    echo "selftest OK: $n_lines files hashed under files/"
  else
    echo "selftest FAIL: device files=$n_files hashed lines=$n_lines"; exit 1
  fi
  exit 0
fi

PKG="$1"; DIR="$2"
adb exec-out run-as "$PKG" sh -c "cd '$DIR' 2>/dev/null && find . -type f -exec sha256sum {} +" < /dev/null | tr -d '\r' | sort -k2

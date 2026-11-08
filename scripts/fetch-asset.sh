#!/usr/bin/env bash
# Downloads a release asset and checks it against a pinned SHA-256: fetch-asset.sh <url> <sha256> <out>.
# Retries a 5xx, a 429 and a dropped connection, since a single CDN error would otherwise fail the whole run; -f fails
# on any other HTTP error with its status instead of saving the error page as the asset.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "usage: $0 <url> <sha256> <out>" >&2
  exit 2
fi
# Some sha256sum builds pass a malformed checksum line, so a digest that is not 64 hex characters is refused here.
if ! [[ "$2" =~ ^[0-9a-f]{64}$ ]]; then
  echo "$0: '$2' is not a SHA-256 digest (64 lowercase hex characters)" >&2
  exit 2
fi
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$3" "$1"
echo "$2  $3" | sha256sum -c -

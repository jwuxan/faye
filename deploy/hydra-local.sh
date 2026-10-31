#!/bin/sh
# Runs Ory Hydra without Docker, configured like deploy/compose.yaml but on SQLite.
# The release binary is downloaded on first use into deploy/.bin/ (git ignored), checked against
# the pinned checksum below, and never committed. State lives in deploy/.bin/hydra.sqlite.
#
#   deploy/hydra-local.sh      # then: deploy/bootstrap.sh, Faye with FAYE_HYDRA_ADMIN_URL=http://localhost:4445
set -eu
version=2.2.0   # keep in step with the image digest in deploy/compose.yaml
dir=$(cd "$(dirname "$0")" && pwd)/.bin
bin=$dir/hydra-$version

case "$(uname -s)/$(uname -m)" in
  Darwin/arm64)  asset=macOS_sqlite_arm64; sum=89732ad1494c57ea39348f62dc5ef5c48de129cd205b17cb12bb67ad27094bb7 ;;
  Darwin/x86_64) asset=macOS_sqlite_64bit; sum=3d40ca8e99e2a6d840130928d5e0245212dba0eea9c26a0d7186ebb4382e673d ;;
  Linux/x86_64)  asset=linux_sqlite_64bit; sum=0fe0539fa452496ac5d98b558f93eb2dbb4cf43733da0b09f8f2bdb4445fc31e ;;
  Linux/aarch64) asset=linux_sqlite_arm64; sum=c499ffdaae0f2ab85eff0567214734515b741a393bef89115c16018f4dc0560d ;;
  *) echo "no pinned Hydra build for $(uname -s)/$(uname -m); use deploy/compose.yaml" >&2; exit 1 ;;
esac

if [ ! -x "$bin" ]; then
  mkdir -p "$dir"
  tgz=$dir/hydra_$version-$asset.tar.gz
  curl -fsSL -o "$tgz" "https://github.com/ory/hydra/releases/download/v$version/hydra_$version-$asset.tar.gz"
  echo "$sum  $tgz" | shasum -a 256 -c - >/dev/null || { rm -f "$tgz"; echo "checksum mismatch for $tgz" >&2; exit 1; }
  tar xzf "$tgz" -C "$dir" hydra
  mv "$dir/hydra" "$bin"
  rm -f "$tgz"
fi

export DSN="sqlite://$dir/hydra.sqlite?_fk=true"
"$bin" migrate sql -e --yes >/dev/null
URLS_SELF_ISSUER=http://localhost:4444 \
URLS_LOGIN=http://localhost:8080/login \
URLS_CONSENT=http://localhost:8080/consent \
SECRETS_SYSTEM=faye-local-development-secret-not-for-production \
  exec "$bin" serve all --dev

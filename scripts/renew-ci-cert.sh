#!/usr/bin/env bash
# Makes a new CI Apple Development certificate for the TestFlight jobs and stores it as the
# IOS_DEV_P12 and IOS_DEV_P12_PASSWORD secrets in every app repo. Run it when the current
# certificate is about to expire (once a year) or when a new app repo needs it: secrets can't be
# read back, so a new repo gets a new certificate shared by all repos.
#
#   scripts/renew-ci-cert.sh csr
#       Makes a private key and ~/Downloads/ci-cert.csr. Upload the CSR at developer.apple.com >
#       Certificates > + > Apple Development, then download the .cer.
#
#   scripts/renew-ci-cert.sh install ~/Downloads/development.cer [owner/repo ...]
#       Checks the certificate against the key, builds the .p12 (with Apple's WWDR G3
#       intermediate), tests it in a temporary keychain, sets both secrets in each repo (default:
#       the repos below), then deletes the key and everything else it made.
#
# Afterwards revoke the previous CI certificate in the developer portal. It is listed as
# "Development" under the account holder's name; tell it apart by its expiry date.
set -euo pipefail

DEFAULT_REPOS=(southstar21dev/Vectra southstar21dev/Volum)
WORK="$HOME/.ci-cert-work"
CSR_OUT="$HOME/Downloads/ci-cert.csr"
WWDR_URL="https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer"

die() { echo "error: $*" >&2; exit 1; }

cmd_csr() {
  [ -e "$WORK/ci.key" ] && die "$WORK/ci.key already exists; finish with 'install' or delete $WORK"
  mkdir -p "$WORK" && chmod 700 "$WORK"
  (umask 077 && openssl genrsa -out "$WORK/ci.key" 2048 2>/dev/null)
  openssl req -new -key "$WORK/ci.key" -subj "/CN=TestFlight CI" -out "$CSR_OUT"
  echo "CSR: $CSR_OUT"
  echo "Upload it at developer.apple.com > Certificates > + > Apple Development, download the .cer,"
  echo "then run: $0 install <path to .cer>"
}

cmd_install() {
  local cer="${1:-}"; shift || true
  [ -f "$cer" ] || die "usage: $0 install <path to .cer> [owner/repo ...]"
  [ -f "$WORK/ci.key" ] || die "no key in $WORK; run '$0 csr' first"
  local repos=("$@"); [ ${#repos[@]} -gt 0 ] || repos=("${DEFAULT_REPOS[@]}")

  trap 'rm -f "$WORK"/*.pem "$WORK"/*.p12 "$WORK"/p12.b64 "$WORK"/p12pass "$WORK"/wwdr.cer; security delete-keychain "$WORK/test.keychain-db" 2>/dev/null || true; rmdir "$WORK" 2>/dev/null || true' EXIT

  openssl x509 -inform DER -in "$cer" -out "$WORK/ci.pem"
  [ "$(openssl x509 -in "$WORK/ci.pem" -noout -pubkey | openssl sha256)" = \
    "$(openssl pkey -in "$WORK/ci.key" -pubout | openssl sha256)" ] \
    || die "$cer does not belong to the key in $WORK"
  openssl x509 -in "$WORK/ci.pem" -noout -subject -enddate

  curl -fsSL "$WWDR_URL" -o "$WORK/wwdr.cer"
  openssl x509 -inform DER -in "$WORK/wwdr.cer" -out "$WORK/wwdr.pem"

  # 3DES/SHA-1 so macOS `security import` accepts it (it rejects the OpenSSL 3+ defaults).
  (umask 077 && openssl rand -hex 24 | tr -d '\n' > "$WORK/p12pass")
  openssl pkcs12 -export -inkey "$WORK/ci.key" -in "$WORK/ci.pem" -certfile "$WORK/wwdr.pem" \
    -name "TestFlight CI" -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 \
    -passout "file:$WORK/p12pass" -out "$WORK/ci.p12"
  base64 -i "$WORK/ci.p12" | tr -d '\n' > "$WORK/p12.b64"

  # Same import as the workflow, in a throwaway keychain kept out of the search list.
  local kc="$WORK/test.keychain-db" pass
  pass=$(openssl rand -hex 12)
  security create-keychain -p "$pass" "$kc"
  security unlock-keychain -p "$pass" "$kc"
  base64 --decode -i "$WORK/p12.b64" -o "$WORK/roundtrip.p12"
  security import "$WORK/roundtrip.p12" -k "$kc" -P "$(cat "$WORK/p12pass")" -f pkcs12 -T /usr/bin/codesign > /dev/null
  security find-identity -v -p codesigning "$kc" | grep -q '"Apple Development' \
    || die "the .p12 does not give a valid Apple Development identity"
  echo "Keychain test passed."

  for repo in "${repos[@]}"; do
    gh secret set IOS_DEV_P12 -R "$repo" < "$WORK/p12.b64"
    gh secret set IOS_DEV_P12_PASSWORD -R "$repo" < "$WORK/p12pass"
    echo "Secrets set in $repo"
  done

  rm -f "$WORK/ci.key" "$CSR_OUT"
  echo "Done. Revoke the previous CI certificate in the developer portal (match it by expiry date)."
}

case "${1:-}" in
  csr) cmd_csr ;;
  install) shift; cmd_install "$@" ;;
  *) die "usage: $0 csr | install <path to .cer> [owner/repo ...]" ;;
esac

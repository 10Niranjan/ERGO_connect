#!/usr/bin/env bash
# Keeps asking Oracle for a free Ampere A1 VM (default 2 OCPU / 12 GB = the whole Always Free A1 allowance) until capacity appears, cycling through availability domains.
# Needs the OCI CLI configured (~/.oci/config). Required env:
#   SUBNET_ID   public subnet OCID (Networking > VCN > Subnets)
# Optional env: COMPARTMENT_ID (default: tenancy), OCPUS=2, MEMORY_GB=12, SSH_PUB=~/.ssh/ergo_oracle.pub, SLEEP=60
set -u
: "${SUBNET_ID:?set SUBNET_ID}"
# Always Free A1 total is 2 OCPU / 12 GB (Oracle docs, checked 2026-11-06)
OCPUS=${OCPUS:-2}; MEMORY_GB=${MEMORY_GB:-12}; SLEEP=${SLEEP:-60}
SSH_PUB=${SSH_PUB:-$HOME/.ssh/ergo_oracle.pub}
COMPARTMENT_ID=${COMPARTMENT_ID:-$(grep -m1 '^tenancy' ~/.oci/config | cut -d= -f2 | tr -d ' ')}
export MSYS_NO_PATHCONV=1
# No local OCI CLI (pip build fails on Python 3.14)? Use Oracle's image; ~/.oci and the public key are mounted in.
if ! command -v oci >/dev/null; then
  oci() { docker run --rm -e OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=True -v "$HOME/.oci:/oracle/.oci:ro" -v "$SSH_PUB:/key.pub:ro" ghcr.io/oracle/oci-cli@sha256:9ecc89fffd09be2c9e1039132112f18b2ca1c9bf09bf01744070c1c42f6d848b "$@"; }
  SSH_PUB_ARG=/key.pub
fi
SSH_PUB_ARG=${SSH_PUB_ARG:-$SSH_PUB}

IMAGE_ID=$(oci compute image list --compartment-id "$COMPARTMENT_ID" --operating-system "Canonical Ubuntu" \
  --shape VM.Standard.A1.Flex --sort-by TIMECREATED --sort-order DESC --limit 1 --query 'data[0].id' --raw-output)
mapfile -t ADS < <(oci iam availability-domain list --compartment-id "$COMPARTMENT_ID" --query 'data[].name' | tr -d '[]", ' | grep .)
echo "image=$IMAGE_ID ads=${ADS[*]}"
[ -n "$IMAGE_ID" ] && [ "$IMAGE_ID" != null ] && [ "${#ADS[@]}" -gt 0 ] || { echo "no image or availability domain found: check ~/.oci/config and the compartment"; exit 1; }

while true; do
  for AD in "${ADS[@]}"; do
    echo "$(date '+%F %T') trying $AD"
    OUT=$(oci compute instance launch --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
      --shape VM.Standard.A1.Flex --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
      --image-id "$IMAGE_ID" --subnet-id "$SUBNET_ID" --assign-public-ip true \
      --display-name ergo-prod --ssh-authorized-keys-file "$SSH_PUB_ARG" \
      --boot-volume-size-in-gbs 100 2>&1)
    if echo "$OUT" | grep -q '"lifecycle-state"'; then
      echo "$OUT"; echo "GOT IT in $AD"; exit 0
    fi
    echo "$OUT" | grep -oE 'Out of host capacity|LimitExceeded|TooManyRequests|NotAuthorizedOrNotFound' | head -1
    case "$OUT" in *LimitExceeded*|*NotAuthorizedOrNotFound*) echo "$OUT"; exit 1;; esac
  done
  sleep "$SLEEP"
done

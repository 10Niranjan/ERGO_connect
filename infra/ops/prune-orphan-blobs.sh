#!/usr/bin/env bash
# After a restore of the database to an earlier point: move the document blobs the restored database does not know about
# out of the way. Blobs are named doc-<document_id> and uploaded with overwrite OFF. The restore rewinds the document_id
# counter, but the blobs created after the restore point still exist, so each upload that lands on one of those ids fails.
#
# Those blobs are real work (supplier quotes, drawings, quality evidence) that the restore rolled back, so they are COPIED
# into a holding container (documents-incident-<date>) first and only then deleted from `documents`. The holding container
# is an incident artefact: same handling and retention as a database dump (docs/BACKUP_AND_RECOVERY.md). A soft-deleted
# source blob is NOT a way back once the app reuses its name, so the copy is the only record.
#
# STOP THE BACKEND FIRST (runbook procedure A, step 0). While a backend is still running against the old database this
# would move files that live documents point to.
#
# Dry run by default. APPLY=1 copies, then deletes.
#
#   PGHOST=<restored server> PGUSER=<admin> ST=<storage account> bash infra/ops/prune-orphan-blobs.sh
#   APPLY=1 ...
#
# Uses the account key (fetched with your Azure login: Contributor on the account), not a Blob data role.
# PRUNE_LAST_ID (skip the database), PRUNE_DB and AZ can be set for testing.
set -euo pipefail

: "${ST:?set ST to the storage account name}"
CONTAINER="${CONTAINER:-documents}"
HOLD="${HOLD:-documents-incident-$(date +%Y%m%d)}"
AZ="${AZ:-az}"
fail() { echo "FAILED: $*" >&2; exit 1; }

if [ -n "${PRUNE_LAST_ID:-}" ]; then
  LAST="$PRUNE_LAST_ID"; SOURCE="PRUNE_LAST_ID (given, not read from a database)"
else
  : "${PGHOST:?set PGHOST (the RESTORED server)}" "${PGUSER:?set PGUSER}"
  if [ -z "${PGPASSWORD:-}" ]; then read -rsp "Password for $PGUSER@$PGHOST: " PGPASSWORD; echo; fi
  export PGPASSWORD MSYS_NO_PATHCONV=1
  CLIENT="${PG_CLIENT_IMAGE:-postgres@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b}"
  SSLMODE="${PGSSLMODE:-verify-full}"
  SSLENV=(); [[ "$SSLMODE" == verify-* ]] && SSLENV=(-e "PGSSLROOTCERT=${PGSSLROOTCERT:-system}")
  DBNAME="${PRUNE_DB:-ergo_connect}"
  LAST="$(docker run --rm -e PGPASSWORD -e "PGSSLMODE=$SSLMODE" ${SSLENV[@]+"${SSLENV[@]}"} "$CLIENT" \
    psql -X -At -v ON_ERROR_STOP=1 -h "$PGHOST" -p "${PGPORT:-5432}" -U "$PGUSER" -d "$DBNAME" \
    -c "select case when is_called then last_value else last_value - 1 end from documents_document_id_seq")"
  SOURCE="$PGHOST/$DBNAME"
fi
# An empty or odd value would make every blob look like an orphan.
[[ "$LAST" =~ ^[0-9]+$ ]] || fail "could not read the restored document counter (got '$LAST')"

KEY="$($AZ storage account keys list --account-name "$ST" --query '[0].value' -o tsv)"
[ -n "$KEY" ] || fail "no account key for $ST"
# '*' = no 5000-name page cap; a partial listing would report success while colliding ids remain.
ALL="$($AZ storage blob list --account-name "$ST" --account-key "$KEY" --container-name "$CONTAINER" --prefix doc- --num-results '*' --query '[].name' -o tsv)"
TOTAL="$(printf '%s\n' "$ALL" | grep -c '^doc-[0-9][0-9]*$' || true)"
ORPHANS="$(printf '%s\n' "$ALL" | sed -n 's/^doc-\([0-9][0-9]*\)$/\1/p' | awk -v last="$LAST" '($1 + 0) > (last + 0) { print "doc-" $1 }')"
COUNT="$(printf '%s' "$ORPHANS" | grep -c . || true)"

echo "restored counter: $LAST (from $SOURCE); blobs in $CONTAINER: $TOTAL; orphans above the counter: $COUNT"
[ "$TOTAL" -gt 0 ] || fail "the container lists no doc-* blobs; wrong account, wrong container or no access. Nothing changed."
[ "$COUNT" -lt "$TOTAL" ] || fail "every blob would be moved, which means the counter is wrong. Nothing changed."
[ "$COUNT" -gt 0 ] || { echo "nothing to do"; exit 0; }
printf '%s\n' "$ORPHANS" | sed -n 1,20p
if [ -z "${APPLY:-}" ]; then echo "(dry run; APPLY=1 copies these $COUNT blobs to $HOLD, then deletes them from $CONTAINER)"; exit 0; fi

$AZ storage container create --account-name "$ST" --account-key "$KEY" --name "$HOLD" --public-access off --only-show-errors >/dev/null
printf '%s\n' "$ORPHANS" | while read -r b; do
  $AZ storage blob copy start --account-name "$ST" --account-key "$KEY" --source-container "$CONTAINER" --source-blob "$b" \
    --destination-container "$HOLD" --destination-blob "$b" --only-show-errors >/dev/null
  # Delete the original only once the copy has completed: otherwise the work would be gone from both places.
  ok=""
  for _ in $(seq 1 30); do
    status="$($AZ storage blob show --account-name "$ST" --account-key "$KEY" --container-name "$HOLD" --name "$b" --query properties.copy.status -o tsv || true)"
    [ "$status" = "success" ] && { ok=1; break; }
    sleep 1
  done
  [ -n "$ok" ] || fail "copy of $b did not complete (status '$status'); stopped before deleting anything further"
  $AZ storage blob delete --account-name "$ST" --account-key "$KEY" --container-name "$CONTAINER" --name "$b" --only-show-errors
done
echo "copied $COUNT blobs to $HOLD and removed them from $CONTAINER. Keep $HOLD under the dump rules."

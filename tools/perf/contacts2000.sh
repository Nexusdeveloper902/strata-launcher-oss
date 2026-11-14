#!/usr/bin/env bash
# CONTACTS2000 fixture for T-PERF-06 (TEST_PLAN §0): 2 000 synthetic contacts in a local account named
# "strata-fixture", so they can be removed without touching real contacts.
#
#   tools/perf/contacts2000.sh load      # about 15-25 min (one content insert per row)
#   tools/perf/contacts2000.sh remove
# Names are generated from fixed syllables (authored by us; no real data): "Fixture Kamalo 0007" and so on.
# Runs on bash 3.2 (macOS) and later.
set -uo pipefail
cmd="${1:-}"
account="strata-fixture"
adb get-state >/dev/null 2>&1 || { echo "no device" >&2; exit 2; }
case "$cmd" in
  load)
    # Idempotent: an earlier (possibly interrupted) load is removed first, so there are exactly 2 000 afterwards.
    adb shell "content delete --uri content://com.android.contacts/raw_contacts --where \"account_name='$account'\"" >/dev/null
    syl=(ka ma lo ri te su na po vi de)
    upper=(Ka Ma Lo Ri Te Su Na Po Vi De)
    for i in $(seq 1 2000); do
      a=${upper[$((i % 10))]}; b=${syl[$(((i / 10) % 10))]}; c=${syl[$(((i / 100) % 10))]}
      name="Fixture ${a}${b}${c} $(printf %04d "$i")"
      # raw contact, then its name row; the raw contact id is the newest one in this account.
      adb shell "content insert --uri content://com.android.contacts/raw_contacts \
        --bind account_type:s:$account --bind account_name:s:$account >/dev/null && \
        id=\$(content query --uri content://com.android.contacts/raw_contacts --projection _id \
          --where \"account_name='$account' AND deleted=0\" --sort '_id DESC' | head -1 | sed 's/.*_id=\([0-9]*\).*/\1/') && \
        content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:\$id \
          --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'$name'"
      [ $((i % 100)) -eq 0 ] && echo "$i / 2000"
    done
    ;;
  remove)
    adb shell "content delete --uri content://com.android.contacts/raw_contacts --where \"account_name='$account'\""
    echo "removed (the provider purges deleted rows in the background)"
    ;;
  *) echo "usage: $0 load|remove" >&2; exit 2 ;;
esac

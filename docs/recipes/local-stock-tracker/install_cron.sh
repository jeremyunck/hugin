#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${1:-${ROOT}/config.json}"

if [[ ! -f "${CONFIG}" ]]; then
  echo "config not found: ${CONFIG}" >&2
  exit 1
fi

if ! command -v crontab >/dev/null 2>&1; then
  echo "crontab not available on this machine" >&2
  exit 1
fi

mapfile -t TIMES < <(python3 - <<'PY' "${CONFIG}"
import json, sys
from pathlib import Path

cfg = json.loads(Path(sys.argv[1]).read_text())
for t in cfg.get("cron_times", []):
    print(t)
PY
)

if [[ "${#TIMES[@]}" -eq 0 ]]; then
  echo "config must define cron_times" >&2
  exit 1
fi

TMP_CRON="$(mktemp)"
trap 'rm -f "${TMP_CRON}"' EXIT
crontab -l 2>/dev/null | grep -v '# Bouw local stock tracker' > "${TMP_CRON}" || true

for time in "${TIMES[@]}"; do
  hour="${time%%:*}"
  minute="${time##*:}"
  printf '%s %s * * * cd %q && ./run.sh %q >> %q 2>&1 # Bouw local stock tracker\n' \
    "${minute}" "${hour}" "${ROOT}" "${CONFIG}" "${ROOT}/cron.log" >> "${TMP_CRON}"
done

crontab "${TMP_CRON}"
echo "Installed ${#TIMES[@]} cron entries."

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

OUTPUT="$("$LAB_DIR/collect_l0.sh" --self-test)"

if [ "$OUTPUT" != "SELF_TEST_OK" ]; then
  printf 'Unexpected self-test output: %s\n' "$OUTPUT" >&2
  exit 1
fi

printf 'collect_l0_self_test: OK\n'

#!/usr/bin/env bash
set -euo pipefail

sound="/System/Library/Sounds/Glass.aiff"
if command -v afplay >/dev/null 2>&1 && [[ -f "$sound" ]]; then
  afplay "$sound"
else
  printf '\a'
fi

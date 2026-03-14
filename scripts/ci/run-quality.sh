#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if find . -type f \( -path "*/src/main/java/*.java" -o -path "*/src/test/java/*.java" \) | grep -q .; then
  BOOTSTRAP=false
else
  BOOTSTRAP=true
fi

echo "quality.bootstrap=${BOOTSTRAP}"
./mvnw -B -ntp -Pquality -Dquality.bootstrap="${BOOTSTRAP}" verify

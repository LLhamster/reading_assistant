#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -f .env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env.local
  set +a
fi

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  export GITHUB_MCP_ENABLED="${GITHUB_MCP_ENABLED:-true}"
else
  export GITHUB_MCP_ENABLED="${GITHUB_MCP_ENABLED:-false}"
  echo "GITHUB_TOKEN is empty; GitHub MCP will stay disabled."
  echo "Create java_src/.env.local from .env.local.example to enable it."
fi

exec mvn spring-boot:run "$@"

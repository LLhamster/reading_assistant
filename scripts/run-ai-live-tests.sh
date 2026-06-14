#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -z "${MODEL_API_KEY:-}" ]]; then
  echo "MODEL_API_KEY is required for live AI tests." >&2
  exit 1
fi

mvn -Dtest=AiPlannerLiveCaseTest -Dai.live=true test
mvn -Dtest=ExternalMcpToolPlannerLiveCaseTest -Dmcp.toolPlanner.live=true test
mvn -Dtest=ExternalMcpAgentShadowLiveTest -Dmcp.agent.shadow.live=true test

echo "AI live/shadow tests passed."

#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -q -Dtest=AiCaseRunnerTest test
mvn -q -Dtest=AiChatShadowCaseTest test
mvn -q -Dtest=ExternalMcpAgentCaseTest,McpToolOrchestratorTest test
mvn -q -Dtest=ExternalMcpToolPlannerServiceTest test

echo "AI mock regression tests passed."

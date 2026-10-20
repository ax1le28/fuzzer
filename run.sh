#!/usr/bin/env sh
# Builds PromptFuzzer if needed and starts it. Extra arguments go to the CLI (default: the dashboard).
set -e
cd "$(dirname "$0")"
if [ ! -f target/promptfuzzer.jar ] || [ -n "$(find src pom.xml -newer target/promptfuzzer.jar -print -quit)" ]; then
  ./mvnw -q -B -DskipTests package
fi
exec java -jar target/promptfuzzer.jar "$@"

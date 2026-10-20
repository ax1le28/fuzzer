@echo off
rem Builds PromptFuzzer and starts it. Extra arguments go to the CLI (default: the dashboard).
cd /d "%~dp0"
if not exist target\promptfuzzer.jar call mvnw.cmd -q -B -DskipTests package || exit /b 1
java -jar target\promptfuzzer.jar %*

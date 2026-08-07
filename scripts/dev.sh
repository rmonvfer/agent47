#!/usr/bin/env bash
# Builds the working tree and runs it in this terminal, passing through any arguments.
#
# The terminal UI needs a real tty, which a Gradle task cannot provide: the build daemon owns
# the console and hands child processes a pipe, so the application would see no terminal and
# fall back to print mode. Building and then exec'ing the launcher keeps this terminal attached.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew --quiet :agent47-app:installDist

exec agent47-app/build/install/agent47/bin/agent47 "$@"

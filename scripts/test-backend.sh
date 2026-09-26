#!/usr/bin/env sh
set -eu
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.11-eclipse-temurin-17 mvn -B verify


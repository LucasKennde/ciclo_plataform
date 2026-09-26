#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

format_backend() {
  if command -v mvn >/dev/null 2>&1; then
    (cd "$PROJECT_DIR" && mvn -q spotless:apply)
    return
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "Maven ou Docker é necessário para formatar o backend." >&2
    exit 1
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$PROJECT_DIR:/workspace" \
    -w /workspace \
    maven:3.9.11-eclipse-temurin-17 \
    mvn -q -Dmaven.repo.local=/tmp/m2 spotless:apply
}

format_frontend() {
  if command -v corepack >/dev/null 2>&1; then
    corepack pnpm --dir "$PROJECT_DIR/frontend" install --frozen-lockfile
    corepack pnpm --dir "$PROJECT_DIR/frontend" run format
    return
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "Node.js com Corepack ou Docker é necessário para formatar o frontend." >&2
    exit 1
  fi

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e COREPACK_HOME=/tmp/corepack \
    -e PNPM_HOME=/tmp/pnpm \
    -v "$PROJECT_DIR/frontend:/workspace" \
    -w /workspace \
    node:22.22.3-alpine \
    sh -c 'corepack pnpm install --frozen-lockfile && corepack pnpm run format'
}

format_backend
format_frontend

echo "Formatação concluída."

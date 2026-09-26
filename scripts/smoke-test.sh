#!/usr/bin/env sh
set -eu

project="ciclo-platform-smoke-${GITHUB_RUN_ID:-$$}"
base_url="${SMOKE_BASE_URL:-http://127.0.0.1:8080}"
admin_email="${BOOTSTRAP_ADMIN_EMAIL:-admin@ciclo.local}"
admin_password="${BOOTSTRAP_ADMIN_PASSWORD:-ChangeMe123!}"
services="postgres rabbitmq redis minio minio-init mailpit migration-preflight identity study ai admin-api gateway"

cleanup() {
  # Only the disposable, uniquely named smoke-test project is removed.
  docker compose -p "$project" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

login() {
  curl -fsS \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$admin_email\",\"password\":\"$admin_password\"}" \
    "$base_url/api/v1/auth/login"
}

wait_for_login() {
  attempts=0
  while [ "$attempts" -lt 90 ]; do
    if response=$(login 2>/dev/null); then
      printf '%s' "$response"
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 2
  done
  docker compose -p "$project" ps --all
  docker compose -p "$project" logs --no-color --tail=200 identity study ai admin-api gateway
  return 1
}

assert_admin_apis() {
  response="$1"
  token=$(printf '%s' "$response" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  if [ -z "$token" ] || ! printf '%s' "$response" | grep -q '"role":"ADMIN"'; then
    echo "O login não retornou um token de administrador." >&2
    return 1
  fi

  auth="Authorization: Bearer $token"
  curl -fsS -H "$auth" "$base_url/api/v1/me" | grep -q '"role":"ADMIN"'
  curl -fsS -H "$auth" "$base_url/api/admin/v1/dashboard" >/dev/null
  curl -fsS -H "$auth" "$base_url/api/admin/v1/users?page=0&size=1" >/dev/null
  curl -fsS -H "$auth" "$base_url/api/admin/v1/settings" >/dev/null
  curl -fsS -H "$auth" "$base_url/api/admin/v1/ai/usage?hours=24" >/dev/null
}

docker compose -p "$project" up -d --build $services
first_login=$(wait_for_login)
assert_admin_apis "$first_login"

docker compose -p "$project" restart identity study ai admin-api gateway
second_login=$(wait_for_login)
assert_admin_apis "$second_login"

unexpected=$(docker compose -p "$project" ps --all --services --filter status=exited | grep -v '^migration-preflight$' || true)
if [ -n "$unexpected" ]; then
  echo "Serviços encerrados durante o smoke test:" >&2
  echo "$unexpected" >&2
  exit 1
fi

echo "Smoke test concluído com migrations e bootstrap idempotentes."

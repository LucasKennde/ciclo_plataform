#!/usr/bin/env sh
set -eu
mkdir -p infra/secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out infra/secrets/jwt-private.pem
openssl rsa -pubout -in infra/secrets/jwt-private.pem -out infra/secrets/jwt-public.pem
chmod 600 infra/secrets/jwt-private.pem
echo "Development JWT keys generated under infra/secrets/."


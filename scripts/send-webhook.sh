#!/usr/bin/env bash
# Sends one WhatsApp Cloud API style message to a gateway, signed with the app secret.
# Works against a local stack or the deployed service. Synthetic data only.
#
#   GATEWAY_URL=https://your-service.onrender.com \
#   WHATSAPP_APP_SECRET=... \
#   scripts/send-webhook.sh 9988000001 "I want a thyroid test on 2026-11-30 at 09:00. My name is Ravi Kumar."
set -euo pipefail

PHONE="${1:?usage: send-webhook.sh <phone> <text>}"
TEXT="${2:?usage: send-webhook.sh <phone> <text>}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
SECRET="${WHATSAPP_APP_SECRET:?set WHATSAPP_APP_SECRET (same value as the gateway)}"

MSG_ID="wamid.sim-$(date +%s%N)-$RANDOM"
BODY=$(printf '{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","messages":[{"from":"%s","id":"%s","timestamp":"%s","type":"text","text":{"body":"%s"}}]}}]}]}' \
  "$PHONE" "$MSG_ID" "$(date +%s)" "$TEXT")
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')"

curl -sS -X POST "$GATEWAY_URL/webhook" \
  -H "Content-Type: application/json" \
  -H "X-Hub-Signature-256: $SIG" \
  --data-binary "$BODY"
echo
echo "reply and bookings: $GATEWAY_URL/conversations/$PHONE"

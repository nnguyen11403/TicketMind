#!/usr/bin/env bash
# Cross-service smoke test for a running TicketMind stack.
#
# Everything below the HTTP boundary already has unit and integration tests.
# What no other suite covers is whether the three services agree with each
# other once they are real processes on a real network: the backend's
# MockRestServiceServer and the RAG service's FakeChat are each other's blind
# spot. A priority value the two sides disagree on, or a renamed field, only
# shows up here.
#
#   docker compose up -d --build
#   ./scripts/smoke-test.sh
#
# Overridable:
#   BACKEND_URL   (default http://localhost:8080)
#   RAG_URL       (default http://localhost:8000)
#   FRONTEND_URL  (default http://localhost:3000)
#   RAG_KEY       (default from $RAG_INTERNAL_API_KEY)

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
RAG_URL="${RAG_URL:-http://localhost:8000}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:3000}"
RAG_KEY="${RAG_KEY:-${RAG_INTERNAL_API_KEY:-}}"

pass=0
fail=0

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail + 1)); }
note() { printf '  \033[33mNOTE\033[0m  %s\n' "$1"; }

# Extracts the FIRST occurrence of a string field. Deliberately not sed with a
# leading `.*`: that is greedy, so on a TicketResponse it returns submitter.id
# instead of the ticket id, which then 404s and looks like a backend bug.
json_first() {
	printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

# Waits for an endpoint to answer 2xx. Services come up in dependency order,
# so the first probe can legitimately take a while.
wait_for() {
	name=$1 url=$2 timeout=${3:-120}
	printf 'waiting for %s ' "$name"
	elapsed=0
	while [ "$elapsed" -lt "$timeout" ]; do
		if curl -fsS -o /dev/null "$url" 2>/dev/null; then
			printf ' up (%ss)\n' "$elapsed"
			return 0
		fi
		printf '.'
		sleep 2
		elapsed=$((elapsed + 2))
	done
	printf ' TIMEOUT after %ss\n' "$timeout"
	return 1
}

echo
echo "=== 1. service health ==="
wait_for "postgres/backend" "$BACKEND_URL/actuator/health" 180 \
	&& ok "backend /actuator/health" || bad "backend never became healthy"
wait_for "rag-service" "$RAG_URL/health" 120 \
	&& ok "rag-service /health" || bad "rag-service never became healthy"
wait_for "frontend" "$FRONTEND_URL/healthz" 90 \
	&& ok "frontend /healthz" || bad "frontend never became healthy"

echo
echo "=== 2. frontend serves the SPA ==="
body=$(curl -fsS "$FRONTEND_URL/" 2>/dev/null)
case "$body" in
	*"<div id=\"root\""*) ok "index.html served at /" ;;
	*) bad "index.html not served at / (got ${#body} bytes)" ;;
esac
# A deep link must fall through to the SPA shell, not 404.
code=$(curl -s -o /dev/null -w '%{http_code}' "$FRONTEND_URL/tickets/does-not-exist")
[ "$code" = "200" ] && ok "deep link /tickets/... falls back to the SPA (200)" \
	|| bad "deep link returned $code, SPA fallback is broken"

# VITE_API_BASE_URL is inlined by Vite at build time, so a wrong value ships a
# broken SPA that every other check still passes: nginx serves it, healthchecks
# are green, and only a real browser fails. Assert on the built bundle itself.
asset=$(curl -fsS "$FRONTEND_URL/" 2>/dev/null | grep -o '/assets/[^"]*\.js' | head -1)
if [ -n "$asset" ]; then
	bundle=$(curl -fsS "$FRONTEND_URL$asset" 2>/dev/null)
	if printf '%s' "$bundle" | grep -qE 'https?://(backend|rag-service|postgres)[:/]'; then
		bad "the bundle points at an internal compose hostname, the browser cannot resolve it"
	else
		ok "no internal compose hostname baked into the bundle"
	fi
	printf '%s' "$bundle" | grep -qF "$BACKEND_URL" \
		&& ok "bundle is built against $BACKEND_URL" \
		|| note "bundle does not reference $BACKEND_URL, check the VITE_API_BASE_URL build arg"
else
	bad "could not find a hashed JS bundle in index.html"
fi

echo
echo "=== 3. RAG service rejects unauthenticated calls ==="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RAG_URL/triage" \
	-H 'Content-Type: application/json' -d '{"ticket_id":"x","title":"t","body":"b"}')
[ "$code" = "401" ] && ok "POST /triage without a key -> 401" \
	|| bad "POST /triage without a key -> $code (expected 401)"

if [ -n "$RAG_KEY" ]; then
	code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RAG_URL/kb/documents" \
		-H "X-Internal-Key: wrong-key" -H 'Content-Type: application/json' \
		-d '{"external_id":"x","title":"t","body":"b"}')
	[ "$code" = "401" ] && ok "wrong internal key -> 401" \
		|| bad "wrong internal key -> $code (expected 401)"
fi

echo
echo "=== 4. end-to-end ticket flow through the backend ==="
suffix=$(date +%s)$$
email="smoke-${suffix}@example.com"
reg_body=$(mktemp)
reg_code=$(curl -s -o "$reg_body" -w '%{http_code}' -X POST "$BACKEND_URL/auth/register" \
	-H 'Content-Type: application/json' \
	-d "{\"email\":\"$email\",\"password\":\"smoke-test-password-1\",\"displayName\":\"Smoke\"}")
reg=$(cat "$reg_body"); rm -f "$reg_body"
token=$(json_first "$reg" accessToken)

if [ -n "$token" ]; then
	ok "registered $email and received an access token"
elif [ "$reg_code" = "429" ]; then
	# Per-IP register limit is 3/hour by design. On a fresh stack (CI) this
	# never fires; locally it does after a few runs. It is a precondition we
	# cannot satisfy, not a product failure, so skip rather than fail, but
	# say so loudly enough that nobody reads it as a pass.
	note "registration rate-limited (HTTP 429), the per-IP cap is 3/hour"
	note "SKIPPING the ticket flow. \`docker compose restart backend\` resets the"
	note "in-memory buckets, or wait for the window to refill."
else
	bad "registration failed (HTTP $reg_code): $reg"
fi

if [ -n "$token" ]; then
	# The SPA parses this response with a strict Zod schema, so a field the
	# backend renames (or never sent) breaks login and registration in the
	# browser while every backend test and every MSW-mocked frontend test still
	# passes. These field names must stay in step with authResponseSchema in
	# frontend/src/api/auth.ts.
	for field in accessToken tokenType expiresAt; do
		printf '%s' "$reg" | grep -q "\"$field\":" \
			&& ok "auth response carries \"$field\" (the SPA's schema requires it)" \
			|| bad "auth response is MISSING \"$field\", the SPA cannot parse it and login will fail"
	done
	printf '%s' "$reg" | grep -qE '"user":\{[^}]*"role":' \
		&& ok "auth response carries user.role" \
		|| bad "auth response is missing user.role, the SPA cannot parse it"

	created=$(curl -fsS -X POST "$BACKEND_URL/tickets" \
		-H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
		-d '{"title":"Card charged twice","description":"I was billed 40 instead of 20 on my last invoice."}' 2>/dev/null)
	ticket_id=$(json_first "$created" id)
	[ -n "$ticket_id" ] && ok "created ticket $ticket_id" || bad "ticket creation failed: $created"

	# Triage is asynchronous, so the 201 carries no verdict yet. A non-null
	# category here would mean someone moved the model call onto the submit
	# path.
	case "$created" in
		*'"category":null'*) ok "201 response is untriaged, as designed" ;;
		*) note "201 carried a non-null category, triage may have gone synchronous" ;;
	esac

	if [ -n "$ticket_id" ]; then
		echo
		echo "    waiting up to 60s for asynchronous triage to land..."
		triaged=""
		for _ in $(seq 1 20); do
			sleep 3
			detail=$(curl -fsS "$BACKEND_URL/tickets/$ticket_id" -H "Authorization: Bearer $token" 2>/dev/null)
			case "$detail" in
				*'"triagedAt":null'*|'') ;;
				*'"triagedAt"'*) triaged="$detail"; break ;;
			esac
		done
		if [ -n "$triaged" ]; then
			ok "triage landed and was written back to the ticket"
			priority=$(json_first "$triaged" priority)
			case "$priority" in
				LOW|MEDIUM|HIGH|CRITICAL)
					ok "priority '$priority' is in the backend enum" ;;
				*)
					bad "priority '$priority' is NOT one of LOW/MEDIUM/HIGH/CRITICAL, the services have drifted" ;;
			esac
			printf '%s' "$triaged" | grep -q '"suggestedResolution":"' \
				&& ok "a suggested resolution was persisted" \
				|| note "no suggested resolution on the ticket"
		else
			note "triage did not land within 60s. Expected without real ANTHROPIC_API_KEY/VOYAGE_API_KEY"
			note "the ticket was still created, which is the graceful-degradation path"
		fi

		# Re-triage is staff-only. Asserted with a submitter token because that
		# costs no model call, the happy path would spend an embedding and a
		# Claude request on every smoke run, and CI has no provider keys anyway.
		code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BACKEND_URL/tickets/$ticket_id/triage" \
			-H "Authorization: Bearer $token")
		[ "$code" = "404" ] && ok "re-triage refuses a submitter (404, not 403, ids stay unguessable)" \
			|| bad "re-triage returned $code for a submitter (expected 404)"

		# Whether or not triage succeeded, a RAG failure must never break the
		# ticket itself. This is the assertion that actually matters.
		still_there=$(curl -s -o /dev/null -w '%{http_code}' "$BACKEND_URL/tickets/$ticket_id" -H "Authorization: Bearer $token")
		[ "$still_there" = "200" ] && ok "ticket survives regardless of RAG outcome" \
			|| bad "ticket unreadable after triage attempt ($still_there)"
	fi
fi

echo
echo "=== 5. CORS between the browser origin and the API ==="
# Nothing in any unit suite covers this, and it is uniquely dangerous: a wrong
# CORS_ALLOWED_ORIGINS leaves every service healthy and every test green while
# the app is completely broken in a real browser. FRONTEND_URL is the origin
# the SPA is actually served from, so it is the one that has to be allowed.
preflight=$(curl -s -i -X OPTIONS "$BACKEND_URL/tickets" \
	-H "Origin: $FRONTEND_URL" \
	-H 'Access-Control-Request-Method: POST' \
	-H 'Access-Control-Request-Headers: authorization,content-type' 2>/dev/null)
printf '%s' "$preflight" | grep -qi "^access-control-allow-origin: $FRONTEND_URL" \
	&& ok "preflight from $FRONTEND_URL is allowed" \
	|| bad "preflight from $FRONTEND_URL was NOT allowed. Check CORS_ALLOWED_ORIGINS against FRONTEND_PORT"
# The refresh cookie is HttpOnly and cross-origin, so credentials must be allowed
# or silent re-auth breaks on every page load.
printf '%s' "$preflight" | grep -qi '^access-control-allow-credentials: true' \
	&& ok "credentials allowed (the tm_refresh cookie needs this)" \
	|| bad "Access-Control-Allow-Credentials missing, silent refresh will fail in the browser"

evil=$(curl -s -i -X OPTIONS "$BACKEND_URL/tickets" \
	-H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: POST' 2>/dev/null)
printf '%s' "$evil" | grep -qi '^access-control-allow-origin' \
	&& bad "an untrusted origin was granted CORS access" \
	|| ok "untrusted origin is refused"

echo
echo "=========================================="
printf 'passed: %s   failed: %s\n' "$pass" "$fail"
echo "=========================================="
[ "$fail" -eq 0 ] || exit 1

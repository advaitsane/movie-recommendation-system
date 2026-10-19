#!/usr/bin/env bash
# Walks one new user through the system via api-gateway, narrating each step: register, search,
# get cold-start recommendations, review, then watch recommendations turn personal once the review
# event arrives (outbox -> Kafka -> recommendation-service). If assistant-service is running
# (`make assistant`), it ends with one streamed assistant answer. Needs curl and jq.
# Usage: scripts/demo.sh   (DEMO_PAUSE=2 slows it down for a recording)
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PAUSE="${DEMO_PAUSE:-0}"
# How long to wait for the review event to reach recommendation-service.
PERSONALIZE_TIMEOUT="${PERSONALIZE_TIMEOUT:-60}"

bold=$'\e[1m' dim=$'\e[2m' green=$'\e[32m' red=$'\e[31m' reset=$'\e[0m'
[[ -t 1 ]] || bold='' dim='' green='' red='' reset=''

step() { sleep "$PAUSE"; printf '\n%s== %s%s\n' "$bold" "$1" "$reset"; }
note() { printf '%s%s%s\n' "$dim" "$1" "$reset"; }
fail() { printf '%s%s%s\n' "$red" "$1" "$reset"; exit 1; }

# call METHOD PATH [BODY]: sets STATUS, BODY and SECS from one request through the gateway.
call() {
  local out
  out=$(curl -s --max-time 20 -X "$1" "${BASE_URL}$2" \
    -H 'Content-Type: application/json' ${TOKEN:+-H "Authorization: Bearer $TOKEN"} \
    ${3:+-d "$3"} -w $'\n%{http_code} %{time_total}')
  BODY=$(sed '$d' <<<"$out")
  read -r STATUS SECS < <(tail -n1 <<<"$out")
  SECS=$(printf '%.2f' "$SECS")
}

# Prints the top recommendations, one line each, with the signal behind each one.
show_recs() {
  jq -r '.[:5][] | "  \(.source | ascii_downcase | .[0:13] | . + " " * (13 - length)) \(.title) (\(.year // "?"))"' <<<"$BODY"
}

command -v jq >/dev/null || fail "jq is required (brew install jq)"
curl -sf --max-time 5 "${BASE_URL}/actuator/health" >/dev/null || fail "api-gateway isn't up at ${BASE_URL}; run make up"

TOKEN=''
EMAIL="demo-$(date +%s)@example.com"

step "1. Register and log in"
call POST /api/users/register "{\"email\":\"$EMAIL\",\"password\":\"password123\",\"displayName\":\"Demo User\"}"
[[ $STATUS == 201 ]] || fail "register: HTTP $STATUS $BODY"
call POST /api/users/login "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
[[ $STATUS == 200 ]] || fail "login: HTTP $STATUS $BODY"
TOKEN=$(jq -r .token <<<"$BODY")
USER_ID=$(jq -r .userId <<<"$BODY")
echo "  user $USER_ID ($EMAIL), JWT issued by user-service, checked by the gateway on every call"

step "2. Search: keywords, then meaning"
# Keyword search sorts by title unless told otherwise; most-voted first reads better here.
call GET "/api/movies/search?q=matrix&size=3&sort=imdbVotes,desc"
echo "  keyword \"matrix\", most-voted first (${SECS}s):"
jq -r '.content[] | "    \(.title) (\(.year))"' <<<"$BODY"
MATRIX_ID=$(jq -r '.content | map(select(.title == "The Matrix"))[0]._id // empty' <<<"$BODY")
call GET "/api/movies/search/vector?q=a%20hacker%20discovers%20reality%20is%20a%20simulation&limit=3"
echo "  vector \"a hacker discovers reality is a simulation\" (${SECS}s):"
if [[ $STATUS == 200 ]]; then
  jq -r '.[:3][] | "    \(.movie.title) (\(.movie.year))  score \(.score * 100 | round / 100)"' <<<"$BODY"
else
  echo "    HTTP $STATUS: the gateway's circuit breaker answered for a slow search-service"
fi
note "  search-service answers from its own copy of the catalog, kept in sync by Kafka events (ADR-0002)"

step "3. Recommendations before any rating: cold start"
call GET "/api/recommendations/${USER_ID}?limit=5"
[[ $STATUS == 200 ]] || fail "recommendations: HTTP $STATUS $BODY"
show_recs
note "  no ratings yet, so the most-liked movies across all users (POPULAR)"

step "4. Review two movies"
[[ -n $MATRIX_ID ]] || fail "couldn't find The Matrix in the search results"
call GET "/api/movies/search?q=blade%20runner&size=10&sort=imdbVotes,desc"
SECOND_ID=$(jq -r '.content | map(select(.title == "Blade Runner"))[0]._id // empty' <<<"$BODY")
[[ -n $SECOND_ID ]] || fail "couldn't find Blade Runner in the search results"
# recommendation-service asks search-service for movies similar to each one, with a 3s timeout. On
# a laptop that has paged mongot's index out, a cold lookup takes longer and the content signal is
# dropped, so warm both lookups first (ADR-0010 Update 9).
for id in "$MATRIX_ID" "$SECOND_ID"; do
  curl -s -o /dev/null --max-time 20 "${BASE_URL}/api/movies/search/${id}/similar?limit=20"
done
for id in "$MATRIX_ID" "$SECOND_ID"; do
  call POST /api/reviews "{\"userId\":\"$USER_ID\",\"movieId\":\"$id\",\"rating\":5,\"reviewText\":\"Loved it.\"}"
  [[ $STATUS == 201 ]] || fail "review: HTTP $STATUS $BODY"
done
REVIEWED_AT=$(date +%s)
echo "  5 stars for The Matrix and Blade Runner"
call POST /api/reviews "{\"userId\":\"$USER_ID\",\"movieId\":\"$MATRIX_ID\",\"rating\":3,\"reviewText\":\"Again.\"}"
echo "  a second review of The Matrix: HTTP $STATUS (one review per user and movie)"
note "  review-service writes the review and its event in one Postgres transaction (the outbox);"
note "  a poller publishes the event to Kafka (ADR-0004)"

step "5. Wait for recommendations to turn personal"
while :; do
  call GET "/api/recommendations/${USER_ID}?limit=5"
  # Personal, and built from both reviews: each arrives as its own event, so a result computed
  # between them can still recommend the second movie.
  if [[ $STATUS == 200 ]] && jq -e --arg a "$MATRIX_ID" --arg b "$SECOND_ID" \
    'any(.[]; .source != "POPULAR") and all(.[]; .movieId != $a and .movieId != $b)' <<<"$BODY" >/dev/null; then
    break
  fi
  (($(date +%s) - REVIEWED_AT >= PERSONALIZE_TIMEOUT)) && fail "still POPULAR after ${PERSONALIZE_TIMEOUT}s"
  sleep 1
done
printf '  %spersonal after %ss%s: outbox -> Kafka -> recommendation-service\n' "$green" $(($(date +%s) - REVIEWED_AT)) "$reset"
show_recs
note "  CONTENT = similar plots to what you liked (search-service vectors); COLLABORATIVE = liked by"
note "  users who liked the same movies; BOTH = both signals (ADR-0005)"

if curl -sf --max-time 3 "${BASE_URL%:*}:8086/actuator/health" >/dev/null; then
  step "6. Ask the assistant"
  QUESTION="I loved The Matrix. Recommend two similar movies, one sentence each."
  echo "  > $QUESTION"
  printf '  '
  curl -sN --max-time 60 -X POST "${BASE_URL}/api/assistant/chat" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"message\":\"$QUESTION\"}" |
    while IFS= read -r line; do
      case "$line" in
        event:*) ev="${line#event:}" ;;
        # jq prints straight out: $(...) would drop a token's trailing newline.
        data:*) [[ $ev == token ]] && jq -j '.text | gsub("\n"; "\n  ")' <<<"${line#data:}"
                [[ $ev == error ]] && printf '%s(error: %s)%s' "$red" "${line#data:}" "$reset" ;;
      esac
    done
  echo
  note "  streamed over SSE; the model called this system's search and recommendation APIs as tools"
else
  note $'\n(assistant-service isn\'t running; `make assistant` adds the last step)'
fi

step "Look behind it"
echo "  traces:     http://localhost:16686  (service: recommendation-service)"
echo "  dashboards: http://localhost:3001   (Movies folder)"

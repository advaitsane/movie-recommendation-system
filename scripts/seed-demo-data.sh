#!/usr/bin/env bash
# Creates a few users with recognizable tastes, through the public API, so the demo's
# recommendations have something to work with: "popular" means liked by several of them, and a
# new user's collaborative peers are fans of the same genres. Safe to rerun: existing users and
# reviews answer 409 and are left as they are. Needs curl and jq.
# Usage: scripts/seed-demo-data.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

# fan id | display name | 5-star movies, separated by ;
FANS=(
  "scifi-1|Sci-fi fan|The Matrix;Alien;Aliens;Terminator 2: Judgment Day;Inception;The Dark Knight"
  "scifi-2|Sci-fi fan|Blade Runner;Alien;The Terminator;Total Recall;Inception;Interstellar"
  "scifi-3|Sci-fi fan|The Matrix;Blade Runner;Aliens;Minority Report;Children of Men;The Dark Knight"
  "crime-1|Crime fan|The Godfather;Pulp Fiction;Goodfellas;The Departed;Heat;The Dark Knight"
  "crime-2|Crime fan|The Godfather;Pulp Fiction;Reservoir Dogs;Casino;Se7en"
  "animation-1|Animation fan|Toy Story;Spirited Away;Finding Nemo;Monsters, Inc.;The Incredibles"
  "animation-2|Animation fan|Toy Story;Spirited Away;Inside Out;Ratatouille;The Lion King"
  "drama-1|Drama fan|The Shawshank Redemption;Forrest Gump;Schindler's List;Inception;Good Will Hunting"
)

command -v jq >/dev/null || { echo "jq is required (brew install jq)"; exit 1; }

# title=id lines; macOS's bash 3.2 has no associative arrays.
MOVIE_IDS=''
# Looks a title up once through keyword search; the most-voted exact match wins.
movie_id() {
  local title="$1" id
  id=$(awk -F= -v t="$title" '$1 == t { print $2; exit }' <<<"$MOVIE_IDS")
  if [[ -z $id ]]; then
    id=$(curl -s --max-time 20 -G "${BASE_URL}/api/movies/search" \
      --data-urlencode "q=${title}" --data "size=50" --data "sort=imdbVotes,desc" |
      jq -r --arg t "$title" '.content | map(select(.title == $t))[0]._id // empty')
  fi
  echo "$id"
}

# post PATH JSON [TOKEN]: POSTs through the gateway and sets STATUS and BODY. Retries a 5xx or a
# timeout: on a cold laptop stack the gateway can give up (503) on a write that still commits, so
# the retry's 409 means it went through.
post() {
  local out attempt
  for attempt in 1 2 3 4 5; do
    out=$(curl -s --max-time 20 -X POST "${BASE_URL}$1" -H 'Content-Type: application/json' \
      ${3:+-H "Authorization: Bearer $3"} -d "$2" -w $'\n%{http_code}')
    STATUS=$(tail -n1 <<<"$out")
    BODY=$(sed '$d' <<<"$out")
    [[ $STATUS == 5* || $STATUS == 000 ]] || return 0
    sleep 3
  done
}

created=0 reviewed=0
for fan in "${FANS[@]}"; do
  IFS='|' read -r id name movies <<<"$fan"
  email="demo-fan-${id}@example.com"
  post /api/users/register "{\"email\":\"$email\",\"password\":\"password123\",\"displayName\":\"$name\"}"
  [[ $STATUS == 201 ]] && ((created++))
  post /api/users/login "{\"email\":\"$email\",\"password\":\"password123\"}"
  token=$(jq -r '.token // empty' <<<"$BODY")
  user_id=$(jq -r '.userId // empty' <<<"$BODY")
  [[ -n $token ]] || { echo "  $email: login failed: HTTP $STATUS $BODY"; exit 1; }

  IFS=';' read -ra titles <<<"$movies"
  for title in "${titles[@]}"; do
    movie=$(movie_id "$title")
    [[ -n $movie ]] || { echo "  not found in the catalog: $title"; continue; }
    # movie_id runs in a subshell, so the cache is filled here.
    MOVIE_IDS+="${title}=${movie}"$'\n'
    post /api/reviews "{\"userId\":\"$user_id\",\"movieId\":\"$movie\",\"rating\":5,\"reviewText\":\"A favourite.\"}" "$token"
    [[ $STATUS == 201 ]] && ((reviewed++))
  done
done
echo "demo data: ${#FANS[@]} fans (${created} new), ${reviewed} new reviews"

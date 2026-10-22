"""The movie-specific half of the evals: which titles an answer recommends, and what the catalog
says about them. The scorers in evalkit only see the dicts this module returns."""

import json
import re
import unicodedata
import urllib.parse
import urllib.request

# The system prompt asks for "title, year and one sentence" per movie, and the model writes
# "**Title (1999)**", "**Title** (1999)" or a list item, plain or in italics, sometimes with the
# rating inside the brackets: "(1999, 8.1)". Only those count as claims, so a title mentioned in
# passing ("unlike *Arrival* (2016), ...") isn't scored as a recommendation.
_YEAR = r"\((\d{4})(?:\s*[,;][^)\n]*)?\)"
_ITEM = r"^\s*(?:[-*•]|\d+[.)])\s+"
_CLAIMS = [
    re.compile(r"\*\*([^*\n]+?)\s*" + _YEAR + r"\s*\*\*"),
    re.compile(r"\*\*([^*\n]+?)\*\*\s*" + _YEAR),
    re.compile(_ITEM + r"(?!\*)([^\n(*]+?)\s*" + _YEAR, re.MULTILINE),
    re.compile(_ITEM + r"\*([^*\n]+?)\*\s*" + _YEAR, re.MULTILINE),
    re.compile(_ITEM + r"\*([^*\n]+?)\s*" + _YEAR + r"\s*\*(?!\*)", re.MULTILINE),
]


def normalize(title: str) -> str:
    # Apostrophes go before the ascii step: the model writes a curly one where the catalog has '.
    text = re.sub(r"['\u2018\u2019`]", "", title)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = text.lower().replace("&", " and ")
    return " ".join(re.sub(r"[^a-z0-9 ]", " ", text).split())


def extract_claims(answer: str) -> list[tuple[str, int]]:
    """(title, year) pairs in the order they first appear, without duplicates."""
    found = []
    for pattern in _CLAIMS:
        for match in pattern.finditer(answer):
            found.append((match.start(), match.group(1).strip(), int(match.group(2))))
    claims, seen = [], set()
    for _, title, year in sorted(found):
        key = (normalize(title), year)
        if key not in seen:
            seen.add(key)
            claims.append((title, year))
    return claims


def _item(title: str, year, genres, rating, movie_id) -> dict:
    return {"id": movie_id, "title": title, "year": year, "genres": genres or [], "imdbRating": rating,
            "ref": f"{normalize(title)}|{year}"}


class Catalog:
    """Exact title lookup in search-service's read model. Keyword search can't do it: a stopword
    title ("Up") matches nothing, and a common one can fall outside the first page. So each year
    is listed once and cached; a year is at most about 1,100 movies."""

    def __init__(self, search_url: str):
        self.search_url = search_url.rstrip("/")
        self._years: dict[int, dict[str, list[dict]]] = {}

    def _year(self, year: int) -> dict[str, list[dict]]:
        if year not in self._years:
            by_title: dict[str, list[dict]] = {}
            page, pages = 0, 1
            while page < pages:
                query = urllib.parse.urlencode({"year": year, "size": 100, "page": page})
                with urllib.request.urlopen(f"{self.search_url}/api/movies/search?{query}", timeout=30) as r:
                    body = json.load(r)
                pages = body["page"]["totalPages"]
                for m in body["content"]:
                    by_title.setdefault(normalize(m["title"]), []).append(
                        _item(m["title"], m.get("year"), m.get("genres"), m.get("imdbRating"), m.get("_id")))
                page += 1
            self._years[year] = by_title
        return self._years[year]

    def resolve(self, claim: tuple[str, int]):
        """The catalog record for a claim, or None and why: wrong_year when the title exists a
        year off (the model's memory, not a tool result), otherwise not_in_catalog."""
        title, year = claim
        key = normalize(title)
        matches = self._year(year).get(key)
        if matches:
            return matches[0], ""
        for other in (year - 1, year + 1):
            if self._year(other).get(key):
                return None, f"wrong_year, catalog has {other}"
        return None, "not_in_catalog"


def tool_results(tools: list[dict], name: str) -> list[dict]:
    """The movies a saved tool call returned to the model, from every successful call to it."""
    return [_item(m["title"], m.get("year"), m.get("genres"), m.get("imdbRating"), m.get("id"))
            for call in tools if call["name"] == name and not call["failed"] and isinstance(call["result"], list)
            for m in call["result"]]


def recommendations(recs_url: str, user_id: str, limit: int) -> list[dict]:
    """What getMyRecommendations returns for this user now. Only for results saved before the
    tool calls were (the list changes when the service or the ratings do)."""
    url = f"{recs_url.rstrip('/')}/api/recommendations/{urllib.parse.quote(user_id)}?limit={limit}"
    with urllib.request.urlopen(url, timeout=30) as r:
        return [_item(m["title"], m.get("year"), m.get("genres"), None, m.get("movieId")) for m in json.load(r)]

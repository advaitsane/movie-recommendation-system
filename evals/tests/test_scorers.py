"""Offline tests for the scorers and the claim extractor: no services, no model calls.

    cd evals && python3 -m unittest discover -s tests -t .
"""

import unittest

import movies
from evalkit import metrics, scorers

# Real answers from gpt-6-luna, 2026-10-20.
NINETIES = ("Here are a few 90s comedies rated 7+ on IMDb:\n\n"
            "- **Groundhog Day (1993)** — 8.1. A weatherman gets stuck reliving the same day.\n"
            "- **Rosencrantz & Guildenstern Are Dead (1990)** — 7.7. Two minor *Hamlet* characters.\n"
            "- **Kikujiro (1999)** — 7.9. A boy and his unlikely, crotchety protector.")
SPANISH = ("De los resultados disponibles, **The Matrix (1999)** podría encajarte: es ciencia ficción "
           "de ritmo más trepidante.")


class ExtractClaims(unittest.TestCase):

    def test_bold_list_items(self):
        self.assertEqual(movies.extract_claims(NINETIES), [
            ("Groundhog Day", 1993), ("Rosencrantz & Guildenstern Are Dead", 1990), ("Kikujiro", 1999)])

    def test_bold_title_in_prose(self):
        self.assertEqual(movies.extract_claims(SPANISH), [("The Matrix", 1999)])

    def test_other_formats(self):
        answer = "1. **Heat** (1995) — crime.\n2. Moon (2009): alone on the moon.\n* Gravity (2013) — space."
        self.assertEqual(movies.extract_claims(answer), [("Heat", 1995), ("Moon", 2009), ("Gravity", 2013)])

    def test_rating_inside_the_brackets(self):
        # The f-thriller-2005-7 answer in the first baseline: five real movies, scored as none.
        answer = ("- **V for Vendetta** (2005, 8.2) — A masked freedom fighter.\n"
                  "- **Sin City** (2005, 8.1) — Three stories.\n- Match Point (2005; 7.7) — An affair.")
        self.assertEqual(movies.extract_claims(answer),
                         [("V for Vendetta", 2005), ("Sin City", 2005), ("Match Point", 2005)])

    def test_italic_list_items(self):
        # The f-70s-crime-above-8 answer in the genres-index gate run: two real movies, scored as none.
        answer = ("- *The Godfather* (1972) — IMDb 9.2; an aging crime boss.\n"
                  "- *Investigation of a Citizen Above Suspicion* (1970) — IMDb 8.1; a detective.\n"
                  "1. *Chinatown (1974)* — a private eye.")
        self.assertEqual(movies.extract_claims(answer), [
            ("The Godfather", 1972), ("Investigation of a Citizen Above Suspicion", 1970), ("Chinatown", 1974)])

    def test_passing_mentions_are_not_claims(self):
        self.assertEqual(movies.extract_claims("Unlike *Arrival*, this one moves fast. It came out in 1996."), [])
        self.assertEqual(movies.extract_claims("It plays like *Arrival* (2016) at half the length."), [])
        self.assertEqual(movies.extract_claims("I can help with movies, but not write a poem."), [])

    def test_duplicates_count_once(self):
        self.assertEqual(movies.extract_claims("**Moon (2009)** and again **Moon (2009)**"), [("Moon", 2009)])

    def test_normalize(self):
        self.assertEqual(movies.normalize("Rosencrantz & Guildenstern Are Dead"),
                         movies.normalize("rosencrantz and guildenstern are dead"))
        self.assertEqual(movies.normalize("Amélie"), "amelie")
        self.assertEqual(movies.normalize("Herod\u2019s Law"), movies.normalize("Herod's Law"))
        self.assertNotEqual(movies.normalize("The Arrival"), movies.normalize("Arrival"))


class Scorers(unittest.TestCase):

    ITEMS = [{"title": "A", "year": 1993, "genres": ["Comedy"], "imdbRating": 8.1},
             {"title": "B", "year": 2001, "genres": ["Drama", "Comedy"], "imdbRating": 6.5}]

    def test_constraints_report_each_violation(self):
        result = scorers.constraints(self.ITEMS, [
            {"field": "year", "op": "between", "value": [1990, 1999]},
            {"field": "genres", "op": "contains", "value": "comedy"},
            {"field": "imdbRating", "op": "gte", "value": 7}], key="title")
        self.assertFalse(result.check.passed)
        self.assertEqual((result.satisfied, result.total), (1, 2))
        self.assertEqual([(k, s["field"]) for k, s, _ in result.violations], [("B", "year"), ("B", "imdbRating")])

    def test_missing_field_fails(self):
        result = scorers.constraints([{"title": "C", "year": None}], [{"field": "year", "op": "gte", "value": 1}], "title")
        self.assertFalse(result.check.passed)

    def test_no_items_satisfies_constraints(self):
        # Too few items is the count check's job, not this one's.
        self.assertTrue(scorers.constraints([], [{"field": "year", "op": "eq", "value": 1}], "title").check.passed)

    def test_grounding(self):
        known = {"A": {"title": "A"}}
        result = scorers.grounding(["A", "Z"], lambda c: (known.get(c), "" if c in known else "not_found"))
        self.assertFalse(result.check.passed)
        self.assertEqual(result.ungrounded, [("Z", "not_found")])
        self.assertEqual(len(result.grounded), 1)

    def test_count_subset_text(self):
        self.assertTrue(scorers.count([], max_items=0).passed)
        self.assertFalse(scorers.count([1], max_items=0).passed)
        self.assertFalse(scorers.subset([{"k": 1}, {"k": 2}], [{"k": 1}], "k").passed)
        self.assertTrue(scorers.text("Directed by Michael Mann.", contains_any=["michael mann"]).passed)
        self.assertFalse(scorers.text("def reverse(s): return s[::-1]", excludes=["def "]).passed)


class Metrics(unittest.TestCase):

    def test_delta_and_sum_by(self):
        before = {("tokens", frozenset({("type", "input")})): 100.0}
        after = {("tokens", frozenset({("type", "input")})): 160.0,
                 ("tokens", frozenset({("type", "output")})): 20.0}
        used = metrics.delta(before, after)
        self.assertEqual(metrics.sum_by(used, "tokens", "type"), {"input": 60.0, "output": 20.0})


if __name__ == "__main__":
    unittest.main()

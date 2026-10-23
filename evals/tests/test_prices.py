"""The prices Grafana's cost panels use (infra/prometheus-rules.yml) match the ones the evals use."""

import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class Prices(unittest.TestCase):

    def test_prometheus_rules_match_prices_json(self):
        rules = (ROOT / "infra" / "prometheus-rules.yml").read_text()
        found = {(model, kind): float(price) for price, model, kind in re.findall(
            r"expr: vector\(([\d.]+)\)\s+labels: \{gen_ai_request_model: ([\w.-]+), gen_ai_token_type: (\w+)\}", rules)}
        prices = json.loads((ROOT / "evals" / "prices.json").read_text())["per_million_tokens"]
        expected = {(model, kind): p[kind] for model, p in prices.items() for kind in ("input", "output")}
        self.assertEqual(found, expected)


if __name__ == "__main__":
    unittest.main()

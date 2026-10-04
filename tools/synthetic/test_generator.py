"""Verification tests for synthetic scenarios and ledger consistency."""

import json
import os
import unittest
from tools.synthetic.generator import generate_scenarios, FIXED_SEED, REFERENCE_DATE


class TestSyntheticScenarios(unittest.TestCase):

    def test_repeatability(self):
        s1 = generate_scenarios()
        s2 = generate_scenarios()
        self.assertEqual(json.dumps(s1, sort_keys=True), json.dumps(s2, sort_keys=True))

    def test_split_separation(self):
        s = generate_scenarios()
        fams = s["document_families"]
        self.assertEqual(len(fams), 12)
        dev = [f for f in fams if f["split"] == "development"]
        holdout = [f for f in fams if f["split"] == "holdout"]
        self.assertEqual(len(dev), 8)
        self.assertEqual(len(holdout), 4)

        dev_ids = {f["family_id"] for f in dev}
        holdout_ids = {f["family_id"] for f in holdout}
        self.assertTrue(dev_ids.isdisjoint(holdout_ids))

    def test_ledger_reconciliation(self):
        s = generate_scenarios()
        # Sum initial movements per (material, location)
        calc_balances = {}
        for m in s["initial_movements"]:
            key = (m["material_code"], m["location_code"])
            calc_balances[key] = calc_balances.get(key, 0.0) + m["quantity"]

        for b in s["initial_balances"]:
            key = (b["material_code"], b["location_code"])
            self.assertIn(key, calc_balances)
            self.assertAlmostEqual(b["quantity"], calc_balances[key])

    def test_expected_outcomes_labeled(self):
        s = generate_scenarios()
        for f in s["document_families"]:
            self.assertIn(f["expected_header_validation"], ["VALID", "INVALID", "REVIEW"])
            self.assertTrue(len(f["expected_header_reason"]) > 5)
            for itm in f["items"]:
                self.assertIn(itm["expected_validation"], ["VALID", "INVALID", "REVIEW"])
                self.assertTrue(len(itm["expected_reason"]) > 5)


if __name__ == "__main__":
    unittest.main()

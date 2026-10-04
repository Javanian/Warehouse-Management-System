"""Unit tests for rendered documents and manifest ground-truth."""

import json
import os
import unittest

ROOT_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST_FILE = os.path.join(ROOT_DIR, "data", "synthetic", "manifest.json")


class TestRenderedDocuments(unittest.TestCase):

    def setUp(self):
        self.assertTrue(os.path.exists(MANIFEST_FILE), "manifest.json does not exist")
        with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
            self.manifest = json.load(f)

    def test_total_count_and_splits(self):
        docs = self.manifest["documents"]
        self.assertEqual(len(docs), 48)

        dev_docs = [d for d in docs if d["split"] == "development"]
        holdout_docs = [d for d in docs if d["split"] == "holdout"]

        self.assertEqual(len(dev_docs), 32)
        self.assertEqual(len(holdout_docs), 16)

        # Ensure split is strictly by family, never cross-contaminated
        dev_families = {d["family_id"] for d in dev_docs}
        holdout_families = {d["family_id"] for d in holdout_docs}
        self.assertEqual(len(dev_families), 8)
        self.assertEqual(len(holdout_families), 4)
        self.assertTrue(dev_families.isdisjoint(holdout_families))

    def test_all_files_exist_on_disk(self):
        for d in self.manifest["documents"]:
            png_full = os.path.join(ROOT_DIR, d["file_png"])
            pdf_full = os.path.join(ROOT_DIR, d["file_pdf"])
            self.assertTrue(os.path.exists(png_full), f"Missing PNG: {png_full}")
            self.assertTrue(os.path.exists(pdf_full), f"Missing PDF: {pdf_full}")
            self.assertGreater(os.path.getsize(png_full), 1000)
            self.assertGreater(os.path.getsize(pdf_full), 1000)

    def test_ground_truth_labels(self):
        for d in self.manifest["documents"]:
            self.assertIn(d["quality"], ["clean", "skewed", "degraded", "annotated"])
            self.assertIn(d["expected_header_validation"], ["VALID", "INVALID", "REVIEW"])
            self.assertTrue(len(d["expected_header_reason"]) > 3)
            self.assertTrue(len(d["line_items"]) > 0)
            for itm in d["line_items"]:
                self.assertIn(itm["expected_validation"], ["VALID", "INVALID", "REVIEW"])
                self.assertTrue(itm["delivered_quantity"] > 0)
                self.assertIn(itm["uom"], ["PC", "BOX", "SET", "M", "DRUM", "PAIL"])
                self.assertEqual(len(itm["bounding_box"]), 4)


if __name__ == "__main__":
    unittest.main()

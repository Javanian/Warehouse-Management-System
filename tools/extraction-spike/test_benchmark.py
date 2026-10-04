"""Tests for extraction spike components, normalizers, evaluators, and negative cases.

Verifies:
- Evaluator handles missing documents and lines without denominator shrinkage (R7)
- Negative regression cases: missing doc, missing line, extra line, wrong PO, wrong UoM, invalid qty
- Provider negative cases: non-existent file, replay without fallback, live model not run (R1)
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from normalizer import (
    normalize_code,
    normalize_date,
    normalize_quantity,
    normalize_uom,
)
from evaluator import evaluate_batch
from providers import OfflineBaselineExtractor, ReplayExtractor, LiveModelExtractor


class TestExtractionSpike(unittest.TestCase):

    def test_normalizers(self):
        # Code normalizer preserves leading zeros
        _, code = normalize_code("  00123-A  ")
        self.assertEqual(code, "00123-A")

        # Date normalizer
        _, d1 = normalize_date("02/10/2026")
        self.assertEqual(d1, "2026-10-02")
        _, d2 = normalize_date("2026-10-02")
        self.assertEqual(d2, "2026-10-02")

        # Quantity normalizer
        _, q1 = normalize_quantity("1.250,50")
        self.assertEqual(float(q1), 1250.50)
        _, q2 = normalize_quantity("50.0")
        self.assertEqual(float(q2), 50.0)

        # UOM normalizer
        _, u1 = normalize_uom("pcs")
        self.assertEqual(u1, "PC")
        _, u2 = normalize_uom("meter")
        self.assertEqual(u2, "M")
        _, u3 = normalize_uom("UNKNOWN_UOM")
        self.assertEqual(u3, "UNKNOWN")

    def test_nonexistent_document_path_raises_filenotfound(self):
        extractor = OfflineBaselineExtractor()
        with self.assertRaises(FileNotFoundError):
            extractor.extract("D:/non_existent_folder/missing_file.png")

    def test_replay_extractor_no_ground_truth_fallback(self):
        # Empty replay records
        extractor = ReplayExtractor(replay_file="non_existent_replay.json")
        res = extractor.extract(doc_path="dummy.png", doc_id="UNKNOWN_DOC_ID")
        self.assertEqual(res["status"], "NOT_FOUND")
        self.assertIn("not found in replay records", res["error"])
        # Header and items must be empty, not synthetic ground truth
        self.assertEqual(res["header"], {})
        self.assertEqual(res["line_items"], [])

    def test_live_extractor_not_run_without_key(self):
        orig_key = os.environ.get("OPENAI_API_KEY")
        try:
            if "OPENAI_API_KEY" in os.environ:
                del os.environ["OPENAI_API_KEY"]
            extractor = LiveModelExtractor()
            res = extractor.extract(doc_path="dummy.png", doc_id="DOC-01")
            self.assertEqual(res["status"], "not_run")
            self.assertIn("OPENAI_API_KEY", res["reason"])
        finally:
            if orig_key is not None:
                os.environ["OPENAI_API_KEY"] = orig_key

    def test_evaluator_missing_document_counted_in_denominator(self):
        # 2 expected documents, but only 1 extracted
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"}
                ],
            },
            {
                "doc_id": "DOC-2",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-02",
                "delivery_note_number": "SJ-02",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-02", "delivered_quantity": 20.0, "uom": "PC"}
                ],
            },
        ]
        # Only DOC-1 is extracted
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    }
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        # Total expected docs = 2, extracted = 1, coverage = 0.5
        self.assertEqual(summary["dataset_summary"]["total_documents_expected"], 2)
        self.assertEqual(summary["dataset_summary"]["missing_documents_count"], 1)
        self.assertEqual(summary["dataset_summary"]["coverage_rate"], 0.5)
        # PO exact match rate is 1 out of 2 = 0.5 (not 1.0!)
        self.assertEqual(summary["header_accuracy"]["po_number_exact_match_rate"], 0.5)
        # Quantity exact match is 1 out of 2 = 0.5
        self.assertEqual(summary["quantity_accuracy"]["exact_numeric_match_rate"], 0.5)

    def test_evaluator_missing_line_counted_in_denominator(self):
        # 1 document with 3 lines in ground truth, but only 1 extracted line
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"},
                    {"line_no": 2, "material_code": "SYN-MAT-02", "delivered_quantity": 20.0, "uom": "PC"},
                    {"line_no": 3, "material_code": "SYN-MAT-03", "delivered_quantity": 30.0, "uom": "PC"},
                ],
            }
        ]
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    }
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        # 3 total lines, 2 missing lines -> recall must be 1/3 approx 0.3333
        self.assertEqual(summary["line_items"]["total_expected_lines"], 3)
        self.assertEqual(summary["line_items"]["missing_lines_count"], 2)
        self.assertEqual(summary["line_items"]["line_recall"], 0.3333)
        # Quantity exact match is 1/3 approx 0.3333 (denominator must be 3, not 1!)
        self.assertEqual(summary["quantity_accuracy"]["total_evaluated_quantities"], 3)
        self.assertEqual(summary["quantity_accuracy"]["exact_numeric_match_rate"], 0.3333)

    def test_evaluator_extra_lines_penalize_precision(self):
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"}
                ],
            }
        ]
        # Extracted has 1 correct line + 1 extra hallucinated line (line_no: 99)
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    },
                    {
                        "line_no": 99,
                        "material_code": {"normalized": "SYN-MAT-GHOST", "confidence": 0.5},
                        "quantity": {"normalized": 99.0, "confidence": 0.5},
                        "uom": {"normalized": "PC", "confidence": 0.5},
                    },
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["line_items"]["extra_lines_count"], 1)
        # Precision: 1 correct / (1 correct + 1 extra) = 0.5
        self.assertEqual(summary["line_items"]["line_precision"], 0.5)

    def test_evaluator_wrong_po_fails_linkage(self):
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-EXPECTED",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"}
                ],
            }
        ]
        # PO extracted is wrong
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-WRONG", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    }
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["header_accuracy"]["po_number_exact_match_rate"], 0.0)
        # PO-item linkage must be 0 because PO did not match!
        self.assertEqual(summary["line_items"]["po_item_linkage_accuracy"], 0.0)

    def test_evaluator_wrong_uom_records_confusion(self):
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"}
                ],
            }
        ]
        # Extracted UOM is KG instead of PC
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "KG", "confidence": 0.9},
                    }
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["uom_accuracy"]["canonical_match_rate"], 0.0)
        self.assertIn("PC -> KG", summary["uom_accuracy"]["confusion_table"])

    def test_evaluator_invalid_quantity(self):
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"}
                ],
            }
        ]
        # Extracted quantity is None / non-numeric
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": None, "confidence": 0.0},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    }
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["quantity_accuracy"]["exact_numeric_match_rate"], 0.0)
        self.assertEqual(summary["quantity_accuracy"]["missing_or_invalid_quantities"], 1)
        self.assertEqual(summary["quantity_accuracy"]["valid_numeric_predictions"], 0)

    def test_line_precision_formula_with_wrong_predictions(self):
        """Wrong predictions on extracted lines must penalize precision."""
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"},
                    {"line_no": 2, "material_code": "SYN-MAT-02", "delivered_quantity": 20.0, "uom": "PC"},
                ],
            }
        ]
        # Extracted 2 lines: line 1 is correct, line 2 has wrong material
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": 10.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    },
                    {
                        "line_no": 2,
                        "material_code": {"normalized": "SYN-MAT-WRONG", "confidence": 0.9},
                        "quantity": {"normalized": 20.0, "confidence": 0.9},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    },
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        # Expected: 1 correct linkage out of 2 extracted lines -> precision = 0.5, recall = 0.5, f1 = 0.5
        self.assertEqual(summary["line_items"]["total_extracted_lines"], 2)
        self.assertEqual(summary["line_items"]["correctly_linked_lines"], 1)
        self.assertEqual(summary["line_items"]["line_precision"], 0.5)
        self.assertEqual(summary["line_items"]["line_recall"], 0.5)
        self.assertEqual(summary["line_items"]["line_f1"], 0.5)

    def test_nan_and_inf_quantity_handling(self):
        """Reject NaN and infinite quantity values."""
        import math
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"},
                    {"line_no": 2, "material_code": "SYN-MAT-02", "delivered_quantity": 20.0, "uom": "PC"},
                ],
            }
        ]
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {
                        "line_no": 1,
                        "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9},
                        "quantity": {"normalized": float("nan"), "confidence": 0.5},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    },
                    {
                        "line_no": 2,
                        "material_code": {"normalized": "SYN-MAT-02", "confidence": 0.9},
                        "quantity": {"normalized": float("inf"), "confidence": 0.5},
                        "uom": {"normalized": "PC", "confidence": 0.9},
                    },
                ],
            }
        ]

        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["quantity_accuracy"]["missing_or_invalid_quantities"], 2)
        self.assertEqual(summary["quantity_accuracy"]["valid_numeric_predictions"], 0)
        self.assertEqual(summary["quantity_accuracy"]["exact_numeric_match_rate"], 0.0)

    def test_duplicate_line_items_in_extraction(self):
        """Track duplicate line numbers in extracted documents."""
        gt_docs = [
            {
                "doc_id": "DOC-1",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-01",
                "delivery_note_number": "SJ-01",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-01", "delivered_quantity": 10.0, "uom": "PC"},
                ],
            }
        ]
        extracted = [
            {
                "doc_id": "DOC-1",
                "header": {
                    "po_number": {"normalized": "SYN-PO-01", "confidence": 0.9},
                    "delivery_note_number": {"normalized": "SJ-01", "confidence": 0.9},
                    "delivery_date": {"normalized": "2026-10-02", "confidence": 0.9},
                    "supplier": {"normalized": "SYN-SUP-01", "confidence": 0.9},
                },
                "line_items": [
                    {"line_no": 1, "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9}, "quantity": {"normalized": 10.0, "confidence": 0.9}, "uom": {"normalized": "PC", "confidence": 0.9}},
                    {"line_no": 1, "material_code": {"normalized": "SYN-MAT-01", "confidence": 0.9}, "quantity": {"normalized": 10.0, "confidence": 0.9}, "uom": {"normalized": "PC", "confidence": 0.9}},
                ],
            }
        ]
        summary = evaluate_batch(gt_docs, extracted)
        self.assertEqual(summary["line_items"]["duplicate_lines_count"], 1)

    def test_live_model_extractor_contract_formatting(self):
        """Verify format_live_model_output matches canonical extraction schema."""
        from providers import format_live_model_output

        raw_llm_json = {
            "po_number": "SYN-PO-2026-001",
            "delivery_note_number": "SJ-2026-001",
            "delivery_date": "2026-10-02",
            "supplier_code": "SYN-SUP-01",
            "line_items": [
                {
                    "line_no": 1,
                    "material_code": "SYN-MAT-001",
                    "quantity": "50.0",
                    "uom": "PCS",
                    "description": "Raw Steel Rods",
                }
            ],
        }

        canonical = format_live_model_output(
            content=raw_llm_json,
            doc_id="DOC-CONTRACT-01",
            doc_path="dummy.png",
            latency_ms=125.5,
            usage={"prompt_tokens": 100, "completion_tokens": 50},
        )

        self.assertEqual(canonical["provider"], "live_openai")
        self.assertEqual(canonical["header"]["po_number"]["normalized"], "SYN-PO-2026-001")
        self.assertEqual(canonical["header"]["delivery_date"]["normalized"], "2026-10-02")
        self.assertEqual(canonical["line_items"][0]["material_code"]["normalized"], "SYN-MAT-001")
        self.assertEqual(canonical["line_items"][0]["quantity"]["normalized"], 50.0)
        self.assertEqual(canonical["line_items"][0]["uom"]["normalized"], "PC")

        # Now pass to evaluate_batch to confirm end-to-end evaluator compatibility
        gt = [
            {
                "doc_id": "DOC-CONTRACT-01",
                "quality": "clean",
                "split": "development",
                "po_number": "SYN-PO-2026-001",
                "delivery_note_number": "SJ-2026-001",
                "delivery_date": "2026-10-02",
                "supplier_code": "SYN-SUP-01",
                "line_items": [
                    {"line_no": 1, "material_code": "SYN-MAT-001", "delivered_quantity": 50.0, "uom": "PC"},
                ],
            }
        ]
        eval_summary = evaluate_batch(gt, [canonical])
        self.assertEqual(eval_summary["header_accuracy"]["po_number_exact_match_rate"], 1.0)
        self.assertEqual(eval_summary["line_items"]["line_precision"], 1.0)
        self.assertEqual(eval_summary["line_items"]["line_recall"], 1.0)


if __name__ == "__main__":
    unittest.main()

"""Evaluator for document extraction spike.

Computes exact match metrics, MAE on valid numerics, UOM confusion,
line precision/recall/F1, PO-item linkage, and confidence calibration.
Strictly accounts for missing documents and lines in denominators (R7 compliance).
"""

from decimal import Decimal
import math
from typing import Any, Dict, List, Optional, Tuple


def evaluate_batch(
    manifest_docs: List[Dict[str, Any]],
    extracted_docs: List[Dict[str, Any]],
) -> Dict[str, Any]:
    total_expected_docs = len(manifest_docs)
    extracted_map = {e.get("doc_id"): e for e in extracted_docs if e.get("doc_id")}

    po_exact_matches = 0
    po_total = 0

    sj_exact_matches = 0
    sj_total = 0

    dt_exact_matches = 0
    dt_total = 0

    sup_exact_matches = 0
    sup_total = 0

    mat_exact_matches = 0
    mat_total = 0

    qty_exact_matches = 0
    qty_total = 0
    qty_valid_numeric_count = 0
    qty_missing_or_invalid_count = 0
    qty_mae_sum = 0.0

    uom_exact_matches = 0
    uom_total = 0
    uom_confusion: Dict[str, int] = {}

    lines_total_gt = 0
    lines_total_extracted = 0
    lines_correct_linkage = 0
    lines_missing_count = 0
    lines_extra_count = 0
    lines_duplicate_count = 0
    missing_docs_count = 0

    # Per quality breakdown
    quality_metrics = {
        q: {
            "expected_docs": 0,
            "extracted_docs": 0,
            "po_exact": 0,
            "po_total": 0,
            "qty_exact": 0,
            "qty_total": 0,
            "mat_exact": 0,
            "mat_total": 0,
            "latency_ms": [],
        }
        for q in ["clean", "skewed", "degraded", "annotated"]
    }

    # Per split breakdown
    split_metrics = {
        "development": {"expected_docs": 0, "extracted_docs": 0, "fields_correct": 0, "fields_total": 0},
        "holdout": {"expected_docs": 0, "extracted_docs": 0, "fields_correct": 0, "fields_total": 0},
    }

    # Confidence calibration bins: [0, 0.5), [0.5, 0.7), [0.7, 0.85), [0.85, 1.01)
    conf_bins = {
        "<0.5": {"correct": 0, "total": 0},
        "0.5-0.7": {"correct": 0, "total": 0},
        "0.7-0.85": {"correct": 0, "total": 0},
        "0.85-1.0": {"correct": 0, "total": 0},
    }

    def assign_bin(conf: float) -> str:
        if conf < 0.5:
            return "<0.5"
        elif conf < 0.7:
            return "0.5-0.7"
        elif conf < 0.85:
            return "0.7-0.85"
        else:
            return "0.85-1.0"

    all_latencies = []

    # Iterate over ALL expected manifest documents (Denominator includes missing documents)
    for gt in manifest_docs:
        doc_id = gt["doc_id"]
        q = gt.get("quality", "clean")
        split = gt.get("split", "development")

        quality_metrics[q]["expected_docs"] += 1
        split_metrics[split]["expected_docs"] += 1

        gt_items = {itm["line_no"]: itm for itm in gt.get("line_items", [])}
        lines_total_gt += len(gt_items)

        ext = extracted_map.get(doc_id)
        if not ext or ext.get("status") in ("FAILED", "NOT_FOUND", "not_run"):
            # Missing or failed document: counts as 0 matches for all expected fields
            missing_docs_count += 1

            po_total += 1
            quality_metrics[q]["po_total"] += 1
            split_metrics[split]["fields_total"] += 1

            sj_total += 1
            split_metrics[split]["fields_total"] += 1

            dt_total += 1
            split_metrics[split]["fields_total"] += 1

            sup_total += 1
            split_metrics[split]["fields_total"] += 1

            for _, gt_itm in gt_items.items():
                lines_missing_count += 1
                qty_total += 1
                qty_missing_or_invalid_count += 1
                quality_metrics[q]["qty_total"] += 1
                split_metrics[split]["fields_total"] += 1

                mat_total += 1
                quality_metrics[q]["mat_total"] += 1
                split_metrics[split]["fields_total"] += 1

                uom_total += 1
            continue

        # Document was extracted
        quality_metrics[q]["extracted_docs"] += 1
        split_metrics[split]["extracted_docs"] += 1

        lat = ext.get("latency_ms", 0.0)
        quality_metrics[q]["latency_ms"].append(lat)
        all_latencies.append(lat)

        ext_header = ext.get("header", {})

        # Header evaluation
        gt_po = gt.get("po_number")
        ext_po_norm = ext_header.get("po_number", {}).get("normalized")
        po_conf = ext_header.get("po_number", {}).get("confidence", 0.0)

        po_total += 1
        quality_metrics[q]["po_total"] += 1
        split_metrics[split]["fields_total"] += 1
        b_po = assign_bin(po_conf)
        conf_bins[b_po]["total"] += 1

        po_matches = False
        if gt_po is None and ext_po_norm is None:
            # Proper abstention on missing PO
            po_matches = True
        elif gt_po is not None and ext_po_norm == gt_po:
            po_matches = True

        if po_matches:
            po_exact_matches += 1
            quality_metrics[q]["po_exact"] += 1
            split_metrics[split]["fields_correct"] += 1
            conf_bins[b_po]["correct"] += 1

        gt_sj = gt.get("delivery_note_number")
        ext_sj_norm = ext_header.get("delivery_note_number", {}).get("normalized")
        sj_conf = ext_header.get("delivery_note_number", {}).get("confidence", 0.0)

        sj_total += 1
        split_metrics[split]["fields_total"] += 1
        b_sj = assign_bin(sj_conf)
        conf_bins[b_sj]["total"] += 1

        if ext_sj_norm == gt_sj:
            sj_exact_matches += 1
            split_metrics[split]["fields_correct"] += 1
            conf_bins[b_sj]["correct"] += 1

        gt_dt = gt.get("delivery_date")
        ext_dt_norm = ext_header.get("delivery_date", {}).get("normalized")
        dt_conf = ext_header.get("delivery_date", {}).get("confidence", 0.0)

        dt_total += 1
        split_metrics[split]["fields_total"] += 1
        b_dt = assign_bin(dt_conf)
        conf_bins[b_dt]["total"] += 1

        if ext_dt_norm == gt_dt:
            dt_exact_matches += 1
            split_metrics[split]["fields_correct"] += 1
            conf_bins[b_dt]["correct"] += 1

        gt_sup = gt.get("supplier_code")
        ext_sup_norm = ext_header.get("supplier", {}).get("normalized")
        sup_conf = ext_header.get("supplier", {}).get("confidence", 0.0)

        sup_total += 1
        split_metrics[split]["fields_total"] += 1
        b_sup = assign_bin(sup_conf)
        conf_bins[b_sup]["total"] += 1

        if ext_sup_norm == gt_sup:
            sup_exact_matches += 1
            split_metrics[split]["fields_correct"] += 1
            conf_bins[b_sup]["correct"] += 1

        # Line items evaluation
        raw_ext_items = ext.get("line_items", [])
        lines_total_extracted += len(raw_ext_items)
        ext_items = {}
        for itm in raw_ext_items:
            l_no = itm.get("line_no")
            if l_no in ext_items:
                lines_duplicate_count += 1
            else:
                ext_items[l_no] = itm

        # Check every ground truth line item (Missing lines remain in denominator)
        for l_no, gt_itm in gt_items.items():
            ext_itm = ext_items.get(l_no)
            if not ext_itm:
                lines_missing_count += 1
                qty_total += 1
                qty_missing_or_invalid_count += 1
                quality_metrics[q]["qty_total"] += 1
                split_metrics[split]["fields_total"] += 1

                mat_total += 1
                quality_metrics[q]["mat_total"] += 1
                split_metrics[split]["fields_total"] += 1

                uom_total += 1
                continue

            # Material code match
            mat_total += 1
            quality_metrics[q]["mat_total"] += 1
            split_metrics[split]["fields_total"] += 1

            gt_mat = gt_itm["material_code"]
            ext_mat = ext_itm["material_code"].get("normalized")
            mat_conf = ext_itm["material_code"].get("confidence", 0.0)
            b_mat = assign_bin(mat_conf)
            conf_bins[b_mat]["total"] += 1

            mat_matches = (ext_mat == gt_mat)
            if mat_matches:
                mat_exact_matches += 1
                quality_metrics[q]["mat_exact"] += 1
                split_metrics[split]["fields_correct"] += 1
                conf_bins[b_mat]["correct"] += 1

            # Quantity match & MAE
            qty_total += 1
            quality_metrics[q]["qty_total"] += 1
            split_metrics[split]["fields_total"] += 1

            gt_qty = float(gt_itm.get("delivered_quantity", 0.0))
            ext_qty = ext_itm["quantity"].get("normalized")
            qty_conf = ext_itm["quantity"].get("confidence", 0.0)
            b_qty = assign_bin(qty_conf)
            conf_bins[b_qty]["total"] += 1

            qty_matches = False
            if ext_qty is not None:
                try:
                    ext_qty_val = float(ext_qty)
                    if math.isnan(ext_qty_val) or math.isinf(ext_qty_val):
                        qty_missing_or_invalid_count += 1
                    else:
                        diff = abs(ext_qty_val - gt_qty)
                        qty_mae_sum += diff
                        qty_valid_numeric_count += 1
                        if diff < 1e-4:
                            qty_matches = True
                            qty_exact_matches += 1
                            quality_metrics[q]["qty_exact"] += 1
                            split_metrics[split]["fields_correct"] += 1
                            conf_bins[b_qty]["correct"] += 1
                except (ValueError, TypeError):
                    qty_missing_or_invalid_count += 1
            else:
                qty_missing_or_invalid_count += 1

            # UOM match
            uom_total += 1
            gt_uom = gt_itm["uom"]
            ext_uom = ext_itm["uom"].get("normalized")
            if ext_uom == gt_uom:
                uom_exact_matches += 1
            else:
                uom_confusion[f"{gt_uom} -> {ext_uom}"] = uom_confusion.get(f"{gt_uom} -> {ext_uom}", 0) + 1

            # Line linkage verification (Requires matching PO, matching Material, and matching Quantity)
            if po_matches and mat_matches and qty_matches:
                lines_correct_linkage += 1

        # Check for extra lines in extraction (precision penalty)
        for l_no in ext_items:
            if l_no not in gt_items:
                lines_extra_count += 1

    # Aggregate percentiles
    all_latencies.sort()
    p50_lat = all_latencies[len(all_latencies) // 2] if all_latencies else 0.0
    p95_idx = int(0.95 * len(all_latencies))
    p95_lat = all_latencies[min(p95_idx, len(all_latencies) - 1)] if all_latencies else 0.0

    # Line Precision, Recall, F1 (Strict: precision denominator is total extracted lines)
    line_precision = round(lines_correct_linkage / max(1, lines_total_extracted), 4)
    line_recall = round(lines_correct_linkage / max(1, lines_total_gt), 4)
    line_f1 = (
        round(2 * line_precision * line_recall / (line_precision + line_recall), 4)
        if (line_precision + line_recall) > 0
        else 0.0
    )

    numeric_mae = round(qty_mae_sum / max(1, qty_valid_numeric_count), 4) if qty_valid_numeric_count > 0 else 0.0
    doc_coverage = round((total_expected_docs - missing_docs_count) / max(1, total_expected_docs), 4)

    return {
        "dataset_summary": {
            "total_documents_expected": total_expected_docs,
            "total_documents_extracted": len(extracted_docs),
            "missing_documents_count": missing_docs_count,
            "coverage_rate": doc_coverage,
            "development_expected_docs": split_metrics["development"]["expected_docs"],
            "holdout_expected_docs": split_metrics["holdout"]["expected_docs"],
        },
        "header_accuracy": {
            "po_number_exact_match_rate": round(po_exact_matches / max(1, po_total), 4),
            "delivery_note_exact_match_rate": round(sj_exact_matches / max(1, sj_total), 4),
            "delivery_date_exact_match_rate": round(dt_exact_matches / max(1, dt_total), 4),
            "supplier_exact_match_rate": round(sup_exact_matches / max(1, sup_total), 4),
        },
        "quantity_accuracy": {
            "exact_numeric_match_rate": round(qty_exact_matches / max(1, qty_total), 4),
            "numeric_mae": numeric_mae,
            "valid_numeric_predictions": qty_valid_numeric_count,
            "missing_or_invalid_quantities": qty_missing_or_invalid_count,
            "total_evaluated_quantities": qty_total,
        },
        "uom_accuracy": {
            "canonical_match_rate": round(uom_exact_matches / max(1, uom_total), 4),
            "confusion_table": uom_confusion,
        },
        "line_items": {
            "total_expected_lines": lines_total_gt,
            "total_extracted_lines": lines_total_extracted,
            "correctly_linked_lines": lines_correct_linkage,
            "missing_lines_count": lines_missing_count,
            "extra_lines_count": lines_extra_count,
            "duplicate_lines_count": lines_duplicate_count,
            "line_precision": line_precision,
            "line_recall": line_recall,
            "line_f1": line_f1,
            "po_item_linkage_accuracy": round(lines_correct_linkage / max(1, lines_total_gt), 4),
        },
        "quality_breakdown": {
            q: {
                "expected_docs": quality_metrics[q]["expected_docs"],
                "extracted_docs": quality_metrics[q]["extracted_docs"],
                "po_exact_match_rate": round(
                    quality_metrics[q]["po_exact"] / max(1, quality_metrics[q]["po_total"]), 4
                ),
                "quantity_exact_match_rate": round(
                    quality_metrics[q]["qty_exact"] / max(1, quality_metrics[q]["qty_total"]), 4
                ),
                "material_code_exact_match_rate": round(
                    quality_metrics[q]["mat_exact"] / max(1, quality_metrics[q]["mat_total"]), 4
                ),
                "p50_latency_ms": (
                    round(sorted(quality_metrics[q]["latency_ms"])[len(quality_metrics[q]["latency_ms"]) // 2], 2)
                    if quality_metrics[q]["latency_ms"]
                    else 0.0
                ),
            }
            for q in ["clean", "skewed", "degraded", "annotated"]
        },
        "split_accuracy": {
            "development_field_accuracy": round(
                split_metrics["development"]["fields_correct"]
                / max(1, split_metrics["development"]["fields_total"]),
                4,
            ),
            "holdout_field_accuracy": round(
                split_metrics["holdout"]["fields_correct"]
                / max(1, split_metrics["holdout"]["fields_total"]),
                4,
            ),
        },
        "confidence_calibration": {
            k: {
                "empirical_accuracy": round(v["correct"] / max(1, v["total"]), 4),
                "total_fields": v["total"],
                "is_calibrated_probability": False,
                "note": "Heuristic OCR confidence, not a formally calibrated probability",
            }
            for k, v in conf_bins.items()
        },
        "latency_profile_ms": {
            "p50": round(p50_lat, 2),
            "p95": round(p95_lat, 2),
            "measurement_type": "actual_wall_clock",
        },
    }

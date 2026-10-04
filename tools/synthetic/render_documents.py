"""Synthetic Delivery Document Renderer for StockFlow.

Renders 48 synthetic delivery notes (surat jalan):
12 document families x 4 quality levels (clean, skewed, degraded, annotated)
across 4 supplier layout styles.
Outputs both PNG (raster) and PDF formats, along with a comprehensive
ground-truth manifest with field coordinates, PO linkage, and validation labels.
All assets are marked SYNTHETIC DEMO ONLY.
"""

import json
import math
import os
import random
from typing import Any, Dict, List, Tuple
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ROOT_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
SYNTHETIC_DIR = os.path.join(ROOT_DIR, "data", "synthetic")
SCENARIOS_FILE = os.path.join(SYNTHETIC_DIR, "scenarios.json")
DOCS_OUTPUT_DIR = os.path.join(SYNTHETIC_DIR, "documents")
PNG_DIR = os.path.join(DOCS_OUTPUT_DIR, "png")
PDF_DIR = os.path.join(DOCS_OUTPUT_DIR, "pdf")
MANIFEST_FILE = os.path.join(SYNTHETIC_DIR, "manifest.json")

PAGE_WIDTH = 1240
PAGE_HEIGHT = 1754  # A4 at 150 DPI approx

QUALITIES = ["clean", "skewed", "degraded", "annotated"]


def get_default_font(size: int = 16) -> ImageFont.ImageFont:
    try:
        # Standard Windows fonts
        for font_name in ["arial.ttf", "calibri.ttf", "segoeui.ttf"]:
            font_path = os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "Fonts", font_name)
            if os.path.exists(font_path):
                return ImageFont.truetype(font_path, size)
    except Exception:
        pass
    return ImageFont.load_default()


def get_bold_font(size: int = 16) -> ImageFont.ImageFont:
    try:
        for font_name in ["arialbd.ttf", "calibrib.ttf", "segoeuib.ttf"]:
            font_path = os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "Fonts", font_name)
            if os.path.exists(font_path):
                return ImageFont.truetype(font_path, size)
    except Exception:
        pass
    return get_default_font(size)


def render_base_document(
    family: Dict[str, Any],
    supplier: Dict[str, Any],
    all_materials: Dict[str, Any],
    doc_num: str,
    delivery_date: str,
) -> Tuple[Image.Image, Dict[str, Any]]:
    """Renders the pristine base delivery document and computes ground-truth coordinates."""
    img = Image.new("RGB", (PAGE_WIDTH, PAGE_HEIGHT), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)

    f_title = get_bold_font(30)
    f_sub = get_bold_font(18)
    f_body = get_default_font(16)
    f_bold = get_bold_font(16)
    f_small = get_default_font(13)
    f_wm = get_bold_font(28)

    layout = supplier.get("layout", "standard_industrial")
    margin_x = 70
    top_y = 50

    # Top Watermark
    wm_text = "[ SYNTHETIC DEMO ONLY - NOT A REAL DOCUMENT ]"
    draw.text((PAGE_WIDTH // 2 - 280, top_y), wm_text, fill=(180, 50, 50), font=f_wm)

    cur_y = top_y + 50
    header_bboxes = {}

    # Header section based on layout
    if layout == "standard_industrial":
        # Bordered supplier header box
        draw.rectangle([margin_x, cur_y, PAGE_WIDTH - margin_x, cur_y + 110], outline=(40, 40, 40), width=2)
        draw.text((margin_x + 20, cur_y + 15), supplier["name"].upper(), fill=(0, 0, 0), font=f_title)
        draw.text((margin_x + 20, cur_y + 55), supplier["address"], fill=(60, 60, 60), font=f_small)
        draw.text((margin_x + 20, cur_y + 75), f"Telp: {supplier['phone']} | Kode: {supplier['code']}", fill=(60, 60, 60), font=f_small)
        cur_y += 130

        # Title
        draw.text((PAGE_WIDTH // 2 - 130, cur_y), "SURAT JALAN / DELIVERY NOTE", fill=(0, 0, 0), font=f_sub)
        cur_y += 40

        # Metadata box (2 columns)
        meta_top = cur_y
        draw.rectangle([margin_x, meta_top, PAGE_WIDTH - margin_x, meta_top + 90], outline=(100, 100, 100), width=1)

        # Col 1
        draw.text((margin_x + 15, meta_top + 15), "No. Surat Jalan :", fill=(0, 0, 0), font=f_bold)
        draw.text((margin_x + 160, meta_top + 15), doc_num, fill=(0, 0, 0), font=f_body)
        header_bboxes["delivery_note_number"] = [margin_x + 160, meta_top + 15, margin_x + 350, meta_top + 35]

        draw.text((margin_x + 15, meta_top + 50), "Tanggal Kirim   :", fill=(0, 0, 0), font=f_bold)
        draw.text((margin_x + 160, meta_top + 50), delivery_date, fill=(0, 0, 0), font=f_body)
        header_bboxes["delivery_date"] = [margin_x + 160, meta_top + 50, margin_x + 300, meta_top + 70]

        # Col 2
        col2_x = PAGE_WIDTH // 2 + 20
        draw.text((col2_x, meta_top + 15), "No. Purchase Order :", fill=(0, 0, 0), font=f_bold)
        po_str = family["po_number"] if family.get("family_id") != "FAM-10" else "(TIDAK DICANTUMKAN)"
        draw.text((col2_x + 180, meta_top + 15), po_str, fill=(0, 0, 0), font=f_body)
        header_bboxes["po_number"] = [col2_x + 180, meta_top + 15, col2_x + 400, meta_top + 35]

        draw.text((col2_x, meta_top + 50), "Tujuan Penerimaan  :", fill=(0, 0, 0), font=f_bold)
        draw.text((col2_x + 180, meta_top + 50), "PT STOCKFLOW LOGISTIK INDONESIA", fill=(0, 0, 0), font=f_body)
        cur_y = meta_top + 120

    elif layout == "compact_grid":
        # Supplier left, meta right
        draw.text((margin_x, cur_y), supplier["name"].upper(), fill=(0, 0, 0), font=f_title)
        draw.text((margin_x, cur_y + 40), supplier["address"], fill=(60, 60, 60), font=f_small)
        draw.text((margin_x, cur_y + 60), f"Kontak: {supplier['phone']}", fill=(60, 60, 60), font=f_small)

        meta_left = PAGE_WIDTH - margin_x - 420
        draw.rectangle([meta_left, cur_y, PAGE_WIDTH - margin_x, cur_y + 100], fill=(245, 245, 245), outline=(60, 60, 60))
        draw.text((meta_left + 15, cur_y + 10), f"SURAT PENGANTAR: {doc_num}", fill=(0, 0, 0), font=f_bold)
        header_bboxes["delivery_note_number"] = [meta_left + 15, cur_y + 10, PAGE_WIDTH - margin_x - 10, cur_y + 30]

        draw.text((meta_left + 15, cur_y + 40), f"Tanggal: {delivery_date}", fill=(0, 0, 0), font=f_body)
        header_bboxes["delivery_date"] = [meta_left + 15, cur_y + 40, meta_left + 250, cur_y + 60]

        po_str = family["po_number"] if family.get("family_id") != "FAM-10" else "PO: BLANK"
        draw.text((meta_left + 15, cur_y + 70), f"PO Ref: {po_str}", fill=(0, 0, 0), font=f_bold)
        header_bboxes["po_number"] = [meta_left + 15, cur_y + 70, PAGE_WIDTH - margin_x - 10, cur_y + 90]
        cur_y += 130

    elif layout == "header_split":
        # Dark top strip
        draw.rectangle([margin_x, cur_y, PAGE_WIDTH - margin_x, cur_y + 60], fill=(50, 50, 50))
        draw.text((margin_x + 20, cur_y + 15), supplier["name"].upper(), fill=(255, 255, 255), font=f_title)
        cur_y += 75

        draw.text((margin_x, cur_y), supplier["address"] + " | " + supplier["phone"], fill=(70, 70, 70), font=f_small)
        cur_y += 35

        draw.line([margin_x, cur_y, PAGE_WIDTH - margin_x, cur_y], fill=(120, 120, 120), width=1)
        cur_y += 20

        draw.text((margin_x, cur_y), "DOKUMEN PENGIRIMAN BARANG", fill=(0, 0, 0), font=f_sub)
        cur_y += 35

        draw.text((margin_x, cur_y), f"NO SURAT JALAN : {doc_num}", fill=(0, 0, 0), font=f_bold)
        header_bboxes["delivery_note_number"] = [margin_x, cur_y, margin_x + 350, cur_y + 20]
        draw.text((PAGE_WIDTH // 2, cur_y), f"TANGGAL : {delivery_date}", fill=(0, 0, 0), font=f_bold)
        header_bboxes["delivery_date"] = [PAGE_WIDTH // 2, cur_y, PAGE_WIDTH // 2 + 250, cur_y + 20]
        cur_y += 30

        po_str = family["po_number"] if family.get("family_id") != "FAM-10" else "PO: -"
        draw.text((margin_x, cur_y), f"NO ORDER PEMBELIAN (PO) : {po_str}", fill=(0, 0, 0), font=f_bold)
        header_bboxes["po_number"] = [margin_x, cur_y, margin_x + 450, cur_y + 20]
        cur_y += 50

    else:  # tabular_boxed
        draw.rectangle([margin_x, cur_y, PAGE_WIDTH - margin_x, cur_y + 90], outline=(0, 0, 0), width=2)
        draw.text((margin_x + 15, cur_y + 15), supplier["name"], fill=(0, 0, 0), font=f_title)
        draw.text((margin_x + 15, cur_y + 55), f"Vendor Code: {supplier['code']} | {supplier['address']}", fill=(60, 60, 60), font=f_small)
        cur_y += 110

        draw.text((PAGE_WIDTH // 2 - 100, cur_y), "BUKTI PENYERAHAN BARANG", fill=(0, 0, 0), font=f_sub)
        cur_y += 40

        box_w = (PAGE_WIDTH - 2 * margin_x) // 3
        draw.rectangle([margin_x, cur_y, margin_x + box_w, cur_y + 50], outline=(0, 0, 0))
        draw.text((margin_x + 10, cur_y + 8), "NO. PENGIRIMAN", fill=(80, 80, 80), font=f_small)
        draw.text((margin_x + 10, cur_y + 26), doc_num, fill=(0, 0, 0), font=f_bold)
        header_bboxes["delivery_note_number"] = [margin_x + 10, cur_y + 26, margin_x + box_w - 5, cur_y + 46]

        draw.rectangle([margin_x + box_w, cur_y, margin_x + 2 * box_w, cur_y + 50], outline=(0, 0, 0))
        draw.text((margin_x + box_w + 10, cur_y + 8), "TANGGAL", fill=(80, 80, 80), font=f_small)
        draw.text((margin_x + box_w + 10, cur_y + 26), delivery_date, fill=(0, 0, 0), font=f_body)
        header_bboxes["delivery_date"] = [margin_x + box_w + 10, cur_y + 26, margin_x + 2 * box_w - 5, cur_y + 46]

        draw.rectangle([margin_x + 2 * box_w, cur_y, PAGE_WIDTH - margin_x, cur_y + 50], outline=(0, 0, 0))
        draw.text((margin_x + 2 * box_w + 10, cur_y + 8), "NO. PURCHASE ORDER", fill=(80, 80, 80), font=f_small)
        po_str = family["po_number"] if family.get("family_id") != "FAM-10" else "N/A"
        draw.text((margin_x + 2 * box_w + 10, cur_y + 26), po_str, fill=(0, 0, 0), font=f_bold)
        header_bboxes["po_number"] = [margin_x + 2 * box_w + 10, cur_y + 26, PAGE_WIDTH - margin_x - 5, cur_y + 46]
        cur_y += 70

    # Table Header
    tbl_left = margin_x
    tbl_right = PAGE_WIDTH - margin_x
    tbl_y = cur_y
    th_h = 35

    draw.rectangle([tbl_left, tbl_y, tbl_right, tbl_y + th_h], fill=(230, 230, 230), outline=(0, 0, 0), width=1)
    col_no = tbl_left + 10
    col_code = tbl_left + 70
    col_desc = tbl_left + 280
    col_qty = tbl_right - 250
    col_uom = tbl_right - 120

    draw.text((col_no, tbl_y + 8), "NO", fill=(0, 0, 0), font=f_bold)
    draw.text((col_code, tbl_y + 8), "KODE BARANG", fill=(0, 0, 0), font=f_bold)
    draw.text((col_desc, tbl_y + 8), "DESKRIPSI BARANG", fill=(0, 0, 0), font=f_bold)
    draw.text((col_qty, tbl_y + 8), "JUMLAH (QTY)", fill=(0, 0, 0), font=f_bold)
    draw.text((col_uom, tbl_y + 8), "SATUAN", fill=(0, 0, 0), font=f_bold)

    cur_row_y = tbl_y + th_h
    line_items_data = []

    for idx, item in enumerate(family["items"], start=1):
        row_h = 45
        draw.rectangle([tbl_left, cur_row_y, tbl_right, cur_row_y + row_h], outline=(150, 150, 150), width=1)

        mat_info = all_materials.get(item.get("target_material_code", item["material_code"]), {})
        mat_desc = mat_info.get("name", "Industrial Supply Item")
        qty_str = f"{item['delivered_qty']:.1f}" if item['delivered_qty'] % 1 != 0 else f"{int(item['delivered_qty'])}"

        draw.text((col_no + 5, cur_row_y + 12), str(idx), fill=(0, 0, 0), font=f_body)
        draw.text((col_code, cur_row_y + 12), item["material_code"], fill=(0, 0, 0), font=f_bold)
        draw.text((col_desc, cur_row_y + 12), mat_desc[:38], fill=(0, 0, 0), font=f_body)
        draw.text((col_qty + 20, cur_row_y + 12), qty_str, fill=(0, 0, 0), font=f_bold)
        draw.text((col_uom + 5, cur_row_y + 12), item["uom"], fill=(0, 0, 0), font=f_body)

        line_items_data.append(
            {
                "line_no": idx,
                "material_code": item["material_code"],
                "target_material_code": item.get("target_material_code", item["material_code"]),
                "description": mat_desc,
                "delivered_quantity": item["delivered_qty"],
                "uom": item["uom"],
                "po_line_reference": item["item_number"],
                "expected_validation": item["expected_validation"],
                "expected_reason": item["expected_reason"],
                "bounding_box": [tbl_left, cur_row_y, tbl_right, cur_row_y + row_h],
            }
        )
        cur_row_y += row_h

    cur_y = cur_row_y + 40

    # Remarks / Notes / Injection
    draw.text((margin_x, cur_y), "Catatan Khusus / Remarks:", fill=(0, 0, 0), font=f_bold)
    cur_y += 25

    remarks = "Barang telah diperiksa dalam kondisi baik saat penyerahan."
    if "injection_payload" in family:
        remarks = family["injection_payload"]
        draw.rectangle([margin_x, cur_y, PAGE_WIDTH - margin_x, cur_y + 45], fill=(255, 245, 245), outline=(200, 50, 50))
        draw.text((margin_x + 10, cur_y + 12), remarks, fill=(180, 0, 0), font=f_small)
        cur_y += 65
    else:
        draw.text((margin_x + 10, cur_y), remarks, fill=(80, 80, 80), font=f_body)
        cur_y += 45

    # Signatures block
    sig_y = max(cur_y + 40, PAGE_HEIGHT - 380)
    sig_col_w = (PAGE_WIDTH - 2 * margin_x) // 3

    for i, title in enumerate(["Diserahkan Oleh (Driver)", "Petugas Keamanan (Security)", "Diterima Gudang (StockFlow)"]):
        bx = margin_x + i * sig_col_w
        draw.rectangle([bx + 10, sig_y, bx + sig_col_w - 10, sig_y + 150], outline=(120, 120, 120), width=1)
        draw.text((bx + 20, sig_y + 10), title, fill=(0, 0, 0), font=f_small)
        draw.line([bx + 25, sig_y + 120, bx + sig_col_w - 25, sig_y + 120], fill=(120, 120, 120), width=1)
        draw.text((bx + 30, sig_y + 125), "( Nama & Tanda Tangan )", fill=(100, 100, 100), font=f_small)

    # Footer Watermark
    draw.text(
        (PAGE_WIDTH // 2 - 250, PAGE_HEIGHT - 60),
        "SYNTHETIC DEMO ASSET - FOR VERIFICATION & PORTFOLIO TESTING ONLY",
        fill=(150, 150, 150),
        font=f_small,
    )

    metadata = {
        "delivery_note_number": doc_num,
        "delivery_date": delivery_date,
        "po_number": family["po_number"] if family.get("family_id") != "FAM-10" else None,
        "supplier_code": supplier["code"],
        "supplier_name": supplier["name"],
        "header_bounding_boxes": header_bboxes,
        "line_items": line_items_data,
    }

    return img, metadata


def apply_transformation(base_img: Image.Image, quality: str) -> Image.Image:
    """Applies quality transformation (clean, skewed, degraded, annotated)."""
    if quality == "clean":
        return base_img.copy()

    elif quality == "skewed":
        # Slight rotation (approx 2.5 to 3 degrees) with white background expand
        angle = random.choice([-2.8, 2.5, -3.2, 3.0])
        rotated = base_img.rotate(angle, resample=Image.BICUBIC, expand=True, fillcolor=(255, 255, 255))
        # Center-crop back to original page dimensions
        w, h = rotated.size
        left = max(0, (w - PAGE_WIDTH) // 2)
        top = max(0, (h - PAGE_HEIGHT) // 2)
        cropped = rotated.crop((left, top, left + PAGE_WIDTH, top + PAGE_HEIGHT))
        return cropped

    elif quality == "degraded":
        # Faded contrast, low blur, compression artifact simulation
        enhancer = ImageEnhance.Contrast(base_img)
        faded = enhancer.enhance(0.75)
        blurred = faded.filter(ImageFilter.GaussianBlur(radius=1.1))
        # Add subtle speckle noise
        noisy = blurred.copy()
        draw = ImageDraw.Draw(noisy)
        for _ in range(300):
            rx = random.randint(0, PAGE_WIDTH - 1)
            ry = random.randint(0, PAGE_HEIGHT - 1)
            draw.point((rx, ry), fill=(160, 160, 160))
        return noisy

    elif quality == "annotated":
        # Stamped inspection mark and handwriting note overlay
        annotated = base_img.copy()
        draw = ImageDraw.Draw(annotated)
        f_stamp = get_bold_font(24)
        f_note = get_bold_font(20)

        # Blue stamp box
        stamp_x = PAGE_WIDTH - 380
        stamp_y = PAGE_HEIGHT - 550
        draw.rectangle([stamp_x, stamp_y, stamp_x + 280, stamp_y + 90], outline=(30, 60, 180), width=3)
        draw.text((stamp_x + 25, stamp_y + 15), "RECEIVED & CHECKED", fill=(30, 60, 180), font=f_stamp)
        draw.text((stamp_x + 55, stamp_y + 50), "WAREHOUSE INBOUND", fill=(30, 60, 180), font=get_bold_font(16))

        # Handwritten style note near table
        draw.text((PAGE_WIDTH // 2 - 100, stamp_y + 20), "OK 02/10 Gdg", fill=(20, 20, 120), font=f_note)
        # Check marks near items
        draw.text((PAGE_WIDTH - 150, 480), "v", fill=(20, 20, 140), font=get_bold_font(30))
        return annotated

    return base_img


def render_all_documents() -> List[Dict[str, Any]]:
    os.makedirs(PNG_DIR, exist_ok=True)
    os.makedirs(PDF_DIR, exist_ok=True)

    with open(SCENARIOS_FILE, "r", encoding="utf-8") as f:
        scenarios = json.load(f)

    suppliers_by_code = {s["code"]: s for s in scenarios["suppliers"]}
    materials_by_code = {m["code"]: m for m in scenarios["materials"]}
    families = scenarios["document_families"]

    manifest = []
    random.seed(20261003)

    for fam_idx, fam in enumerate(families, start=1):
        fam_id = fam["family_id"]
        sup_code = fam.get("doc_supplier_code", fam["supplier_code"])
        supplier = suppliers_by_code[sup_code]

        doc_num = f"SJ-{2026}-{fam_idx:03d}"
        delivery_date = "2026-10-02"

        # Render base image once per family
        base_img, meta = render_base_document(
            fam, supplier, materials_by_code, doc_num, delivery_date
        )

        for quality in QUALITIES:
            doc_id = f"SYN-DOC-{fam_id}-{quality}"
            png_filename = f"{doc_id}.png"
            pdf_filename = f"{doc_id}.pdf"
            png_path = os.path.join(PNG_DIR, png_filename)
            pdf_path = os.path.join(PDF_DIR, pdf_filename)

            # Apply quality transformation
            final_img = apply_transformation(base_img, quality)

            # Save PNG
            final_img.save(png_path, format="PNG", optimize=True)

            # Save PDF directly from PIL
            final_img.save(pdf_path, format="PDF", resolution=150.0)

            manifest_entry = {
                "doc_id": doc_id,
                "family_id": fam_id,
                "case_name": fam["case_name"],
                "split": fam["split"],
                "quality": quality,
                "supplier_code": supplier["code"],
                "supplier_name": supplier["name"],
                "delivery_note_number": doc_num,
                "delivery_date": delivery_date,
                "po_number": meta["po_number"],
                "file_png": os.path.relpath(png_path, ROOT_DIR).replace("\\", "/"),
                "file_pdf": os.path.relpath(pdf_path, ROOT_DIR).replace("\\", "/"),
                "header_fields": meta["header_bounding_boxes"],
                "line_items": meta["line_items"],
                "expected_header_validation": fam["expected_header_validation"],
                "expected_header_reason": fam["expected_header_reason"],
                "watermark": "SYNTHETIC DEMO ONLY",
            }
            manifest.append(manifest_entry)

    with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
        json.dump(
            {
                "metadata": {
                    "total_documents": len(manifest),
                    "development_documents": sum(1 for m in manifest if m["split"] == "development"),
                    "holdout_documents": sum(1 for m in manifest if m["split"] == "holdout"),
                    "qualities": QUALITIES,
                    "generated_at": "2026-10-03",
                    "note": "Anotasi tulisan tangan sintetis adalah overlay terlabel untuk boundary test, bukan representasi handwriting riil.",
                },
                "documents": manifest,
            },
            f,
            indent=2,
        )

    print(f"[render] Successfully rendered {len(manifest)} documents (PNG & PDF).")
    print(f"[render] Manifest saved to {MANIFEST_FILE}")
    return manifest


if __name__ == "__main__":
    render_all_documents()

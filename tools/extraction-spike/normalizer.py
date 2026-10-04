"""Deterministic field normalizers for warehouse delivery documents.

Enforces strict canonical matching rules:
- Leading zeros and exact case are preserved for material codes
- Whitespace is trimmed and normalized
- Dates are normalized to YYYY-MM-DD
- Numbers are converted to exact Decimal with scale
- UOMs are mapped to canonical codes or marked UNKNOWN (no implicit conversion)
"""

import re
from decimal import Decimal, InvalidOperation
from typing import Any, Optional, Tuple

CANONICAL_UOMS = {"PC", "BOX", "SET", "ROLL", "PAIR", "KG", "L", "M", "M2", "DRUM", "PAIL"}

UOM_ALIASES = {
    "PCS": "PC",
    "PIECE": "PC",
    "PIECES": "PC",
    "BUAH": "PC",
    "BTR": "PC",
    "MTR": "M",
    "METER": "M",
    "DRM": "DRUM",
    "PL": "PAIL",
    "BX": "BOX",
}


def normalize_code(raw: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """Normalizes identifiers (PO number, supplier code, material code).

    Preserves leading zeros and uppercase letters.
    Returns (raw_value, normalized_value).
    """
    if not raw or not isinstance(raw, str):
        return None, None
    raw_str = raw.strip()
    if not raw_str:
        return None, None
    norm = re.sub(r"\s+", "", raw_str).upper()
    return raw_str, norm


def normalize_date(raw: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """Normalizes dates in DD/MM/YYYY or YYYY-MM-DD format to YYYY-MM-DD."""
    if not raw or not isinstance(raw, str):
        return None, None
    raw_str = raw.strip()
    # Check YYYY-MM-DD
    m1 = re.match(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$", raw_str)
    if m1:
        y, m, d = int(m1.group(1)), int(m1.group(2)), int(m1.group(3))
        return raw_str, f"{y:04d}-{m:02d}-{d:02d}"

    # Check DD/MM/YYYY
    m2 = re.match(r"^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$", raw_str)
    if m2:
        d, m, y = int(m2.group(1)), int(m2.group(2)), int(m2.group(3))
        return raw_str, f"{y:04d}-{m:02d}-{d:02d}"

    return raw_str, None


def normalize_quantity(raw: Any) -> Tuple[Optional[str], Optional[Decimal]]:
    """Parses decimal quantity, handling commas as decimal or thousands separators."""
    if raw is None:
        return None, None
    raw_str = str(raw).strip()
    if not raw_str:
        return None, None

    clean = raw_str.replace(" ", "")
    # Indonesian/European 1.000,50 -> 1000.50
    if "." in clean and "," in clean:
        if clean.find(".") < clean.find(","):
            clean = clean.replace(".", "").replace(",", ".")
        else:
            clean = clean.replace(",", "")
    elif "," in clean and "." not in clean:
        clean = clean.replace(",", ".")

    try:
        val = Decimal(clean)
        return raw_str, val
    except InvalidOperation:
        return raw_str, None


def normalize_uom(raw: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """Normalizes UOM to canonical set. Does not convert units."""
    if not raw or not isinstance(raw, str):
        return None, None
    raw_str = raw.strip()
    upper = raw_str.upper()
    if upper in CANONICAL_UOMS:
        return raw_str, upper
    if upper in UOM_ALIASES:
        return raw_str, UOM_ALIASES[upper]
    return raw_str, "UNKNOWN"

"""Summarise a Lodge Billing data export (Reports > Export all data).

Reads one export folder, checks it against the documented format before trusting it,
and prints occupancy, revenue, payments, no-shows and repeat guests for the period.

Usage (Windows cmd, from the project folder):
    py -m pip install -r analysis\\requirements.txt
    py analysis\\analyze_export.py data                      # newest export in data\\
    py analysis\\analyze_export.py data\\lodge-export-2026-10-30_2130
    py analysis\\analyze_export.py data --json > summary.json

Exit codes: 0 = report printed, 2 = the export is missing, incomplete or in an unknown format.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pandas as pd

LOG = logging.getLogger("analyze_export")

SUPPORTED_FORMAT = 1

# The contract from the README ("Data export format"). If the app ever exports something
# different, we want a clear error here, not a KeyError halfway through the maths.
EXPECTED_COLUMNS: dict[str, list[str]] = {
    "bookings.csv": ["booking_ref", "status", "room_number", "room_type", "check_in", "check_out", "nights",
                     "nightly_rate_k", "vat_percent", "has_company", "company_pays_room", "guest_key",
                     "created_by", "created_at"],
    "folio_lines.csv": ["entry_id", "booking_ref", "kind", "category", "amount_k", "payer", "posted_at",
                        "posted_by", "voided"],
    "daily_stats.csv": ["date", "rooms", "rooms_sold", "occupancy_pct", "room_revenue_k", "adr_k", "revpar_k",
                        "payments_k"],
}

# Stays where the guest actually turned up (the base for length of stay and the no-show rate)
ARRIVED = {"CHECKED_IN", "CHECKED_OUT"}


class ExportError(Exception):
    """The export cannot be trusted: missing, half-copied, or a format this script does not know."""


@dataclass(frozen=True)
class Export:
    folder: Path
    exported_at: str
    bookings: pd.DataFrame
    folio_lines: pd.DataFrame
    daily: pd.DataFrame


# --------------------------------------------------------------------------- loading


def find_export(path: Path) -> Path:
    """Accept an export folder itself, or a folder holding several (then pick the newest)."""
    if (path / "manifest.json").is_file():
        return path
    if not path.is_dir():
        raise ExportError(f"{path} does not exist or is not a folder.")
    # Names are lodge-export-YYYY-MM-DD_HHmm, so sorting by name is sorting by time.
    # Only folders WITH a manifest count: one without it never finished writing.
    candidates = sorted(p for p in path.glob("lodge-export-*") if (p / "manifest.json").is_file())
    if not candidates:
        raise ExportError(f"No finished export (a lodge-export-* folder with manifest.json) in {path}.")
    LOG.info("Using the newest export: %s", candidates[-1].name)
    return candidates[-1]


def read_manifest(folder: Path) -> dict[str, Any]:
    try:
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise ExportError(f"Cannot read {folder / 'manifest.json'}: {e}") from e

    version = manifest.get("format_version")
    if version != SUPPORTED_FORMAT:
        raise ExportError(f"Export format {version} is not supported (this script reads {SUPPORTED_FORMAT}). "
                          "Update the script or export again from a matching app version.")
    listed = {f["file"]: f for f in manifest.get("files", [])}
    for name, columns in EXPECTED_COLUMNS.items():
        if name not in listed:
            raise ExportError(f"manifest.json does not list {name}.")
        if listed[name].get("columns") != columns:
            raise ExportError(f"{name} has columns {listed[name].get('columns')}, expected {columns}.")
    return manifest


def read_csv(folder: Path, name: str, expected_rows: int, **kwargs: Any) -> pd.DataFrame:
    path = folder / name
    if not path.is_file():
        raise ExportError(f"{name} is listed in the manifest but missing from {folder}.")
    # keep_default_na=False: an empty guest_key stays "" instead of becoming NaN
    df = pd.read_csv(path, encoding="utf-8", keep_default_na=False, **kwargs)
    if list(df.columns) != EXPECTED_COLUMNS[name]:
        raise ExportError(f"{name} header does not match the manifest.")
    if len(df) != expected_rows:
        raise ExportError(f"{name} has {len(df)} rows but the manifest says {expected_rows}: "
                          "the file was changed or only partly copied.")
    return df


def load_export(path: Path) -> Export:
    folder = find_export(path)
    manifest = read_manifest(folder)
    rows = {f["file"]: int(f["rows"]) for f in manifest["files"]}

    # Explicit types: guessing would turn room "101" into the number 101 and lose a room "01A".
    # Money is read as float. Fine for a summary rounded to 2 decimals; a ledger that must add up
    # to the ngwee exactly would use decimal.Decimal instead.
    bookings = read_csv(folder, "bookings.csv", rows["bookings.csv"],
                        dtype={"booking_ref": str, "status": str, "room_number": str, "room_type": str,
                               "nights": "int64", "nightly_rate_k": "float64", "guest_key": str},
                        parse_dates=["check_in", "check_out"])
    folio = read_csv(folder, "folio_lines.csv", rows["folio_lines.csv"],
                     dtype={"booking_ref": str, "kind": str, "category": str, "amount_k": "float64", "payer": str},
                     parse_dates=["posted_at"])
    daily = read_csv(folder, "daily_stats.csv", rows["daily_stats.csv"],
                     dtype={"rooms": "int64", "rooms_sold": "int64", "room_revenue_k": "float64",
                            "payments_k": "float64"},
                     parse_dates=["date"])
    # CSV has no booleans; Java wrote "true"/"false"
    folio["voided"] = folio["voided"].astype(str).str.lower().eq("true")
    return Export(folder, str(manifest.get("exported_at", "?")), bookings, folio, daily)


# --------------------------------------------------------------------------- analysis


def summarise(export: Export) -> dict[str, Any]:
    b, f, d = export.bookings, export.folio_lines, export.daily

    # Occupancy over a period is total rooms sold / total rooms available, NOT the average of
    # the daily percentages (that would weigh a 3-room day the same as a 30-room day).
    rooms_available = int(d["rooms"].sum())
    rooms_sold = int(d["rooms_sold"].sum())
    revenue = float(d["room_revenue_k"].sum())

    arrived = b[b["status"].isin(ARRIVED)]
    due = b[b["status"].isin(ARRIVED | {"NO_SHOW"})]   # guests who were expected to turn up
    no_shows = int((b["status"] == "NO_SHOW").sum())

    live = f[~f["voided"]]
    paid = live[live["kind"] == "PAYMENT"].groupby("category")["amount_k"].sum()
    refunded = live[live["kind"] == "REFUND"].groupby("category")["amount_k"].sum()
    by_method = paid.sub(refunded, fill_value=0).round(2)

    # Repeat guests: same guest_key on more than one real stay (cancelled ones do not count;
    # an empty key means no usable phone number, so that guest cannot be matched)
    keyed = b[(b["guest_key"] != "") & (b["status"] != "CANCELLED")]
    stays_per_guest = keyed.groupby("guest_key").size()

    return {
        "export": export.folder.name,
        "exported_at": export.exported_at,
        "period": {
            "from": d["date"].min().date().isoformat() if len(d) else None,
            "to": d["date"].max().date().isoformat() if len(d) else None,
            "days": len(d),
        },
        "occupancy_pct": round(100 * rooms_sold / rooms_available, 1) if rooms_available else 0.0,
        "room_nights_sold": rooms_sold,
        "room_revenue_k": round(revenue, 2),
        "adr_k": round(revenue / rooms_sold, 2) if rooms_sold else 0.0,
        "revpar_k": round(revenue / rooms_available, 2) if rooms_available else 0.0,
        "payments_k": round(float(d["payments_k"].sum()), 2),
        "stay_payments_by_method_k": {k: float(v) for k, v in by_method.items()},
        "bookings_by_status": {k: int(v) for k, v in b["status"].value_counts().sort_index().items()},
        "no_show_rate_pct": round(100 * no_shows / len(due), 1) if len(due) else 0.0,
        "avg_length_of_stay_nights": round(float(arrived["nights"].mean()), 2) if len(arrived) else 0.0,
        "guests": int(len(stays_per_guest)),
        "repeat_guests": int((stays_per_guest > 1).sum()),
    }


def print_report(s: dict[str, Any]) -> None:
    p = s["period"]
    print(f"Lodge Billing export {s['export']} (exported {s['exported_at']})")
    print(f"Period: {p['from']} to {p['to']} ({p['days']} days)\n")
    print(f"  Occupancy           {s['occupancy_pct']:>10.1f} %   ({s['room_nights_sold']} room nights sold)")
    print(f"  Room revenue        K {s['room_revenue_k']:>10,.2f}")
    print(f"  ADR                 K {s['adr_k']:>10,.2f}   (revenue per room sold)")
    print(f"  RevPAR              K {s['revpar_k']:>10,.2f}   (revenue per room available)")
    print(f"  Payments received   K {s['payments_k']:>10,.2f}")
    for method, amount in s["stay_payments_by_method_k"].items():
        print(f"    {method.replace('_', ' ').lower():<17} K {amount:>10,.2f}")
    print()
    print("  Bookings: " + ", ".join(f"{k.replace('_', ' ').lower()} {v}" for k, v in s["bookings_by_status"].items()))
    print(f"  No-show rate        {s['no_show_rate_pct']:>10.1f} %")
    print(f"  Average stay        {s['avg_length_of_stay_nights']:>10.2f} nights")
    print(f"  Repeat guests       {s['repeat_guests']:>10} of {s['guests']} guests with a phone number")


# --------------------------------------------------------------------------- entry point


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Summarise a Lodge Billing data export.")
    parser.add_argument("path", type=Path, help="an export folder, or a folder of exports (newest is used)")
    parser.add_argument("--json", action="store_true", help="print the summary as JSON instead of text")
    parser.add_argument("-v", "--verbose", action="store_true", help="show which files are read")
    args = parser.parse_args(argv)
    # Logs go to stderr, so `--json > summary.json` stays valid JSON
    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(levelname)s %(message)s", stream=sys.stderr)
    try:
        summary = summarise(load_export(args.path))
    except ExportError as e:
        LOG.error("%s", e)
        return 2
    if args.json:
        json.dump(summary, sys.stdout, indent=2)
        print()
    else:
        print_report(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())

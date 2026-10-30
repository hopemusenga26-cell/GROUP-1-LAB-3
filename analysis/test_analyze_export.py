"""Tests for analyze_export.py. Run from the project folder:  py -m pytest analysis"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import analyze_export as ax

BOOKINGS = ax.EXPECTED_COLUMNS["bookings.csv"]
FOLIO = ax.EXPECTED_COLUMNS["folio_lines.csv"]
DAILY = ax.EXPECTED_COLUMNS["daily_stats.csv"]


def write_export(parent: Path, name: str, bookings: list[str], folio: list[str], daily: list[str],
                 version: int = 1, manifest: bool = True) -> Path:
    """An export folder exactly as the app writes it (header row + data rows + manifest last)."""
    folder = parent / name
    folder.mkdir(parents=True)
    files = []
    for file, header, rows in (("bookings.csv", BOOKINGS, bookings), ("folio_lines.csv", FOLIO, folio),
                               ("daily_stats.csv", DAILY, daily)):
        (folder / file).write_text("\r\n".join([",".join(header), *rows]) + "\r\n", encoding="utf-8")
        files.append({"file": file, "rows": len(rows), "columns": header})
    if manifest:
        (folder / "manifest.json").write_text(json.dumps(
            {"exported_at": "2026-10-30T21:30", "format_version": version, "files": files}), encoding="utf-8")
    return folder


def booking(ref: str, status: str, nights: int, key: str) -> str:
    return f"{ref},{status},101,STANDARD,2026-10-20,2026-10-{20 + nights},{nights},350.00,16,false,false,{key},mary,2026-10-19T09:00"


SAMPLE_BOOKINGS = [
    booking("BK0001", "CHECKED_OUT", 2, "aaaa"),
    booking("BK0002", "CHECKED_IN", 4, "aaaa"),       # same guest again: a repeat guest
    booking("BK0003", "NO_SHOW", 1, "bbbb"),
    booking("BK0004", "CANCELLED", 1, "cccc"),        # cancelled: not a stay, not a repeat
    booking("BK0005", "CHECKED_OUT", 3, ""),          # no phone number: cannot be matched
]
SAMPLE_FOLIO = [
    "1,BK0001,PAYMENT,CASH,700.00,GUEST,2026-10-20T10:00,mary,false",
    "2,BK0001,REFUND,CASH,50.00,GUEST,2026-10-22T10:00,mary,false",
    "3,BK0002,PAYMENT,CARD,1000.00,GUEST,2026-10-21T10:00,mary,false",
    "4,BK0002,PAYMENT,CARD,9999.00,GUEST,2026-10-21T10:05,mary,true",   # voided typo
]
SAMPLE_DAILY = [
    "2026-10-20,10,3,30.0,1050.00,350.00,105.00,700.00",
    "2026-10-21,30,9,30.0,3150.00,350.00,105.00,1000.00",
]


@pytest.fixture
def export(tmp_path: Path) -> Path:
    return write_export(tmp_path, "lodge-export-2026-10-30_2130", SAMPLE_BOOKINGS, SAMPLE_FOLIO, SAMPLE_DAILY)


def test_summary_figures(export: Path) -> None:
    s = ax.summarise(ax.load_export(export))
    assert s["period"] == {"from": "2026-10-20", "to": "2026-10-21", "days": 2}
    assert s["occupancy_pct"] == 30.0           # 12 sold / 40 available, weighted by rooms
    assert s["room_revenue_k"] == 4200.00
    assert s["adr_k"] == 350.00
    assert s["revpar_k"] == 105.00
    assert s["stay_payments_by_method_k"] == {"CARD": 1000.0, "CASH": 650.0}   # refund off, void ignored
    assert s["no_show_rate_pct"] == 25.0        # 1 no-show of 4 guests due (cancelled is not due)
    assert s["avg_length_of_stay_nights"] == 3.0
    assert (s["guests"], s["repeat_guests"]) == (2, 1)


def test_newest_finished_export_is_chosen(tmp_path: Path) -> None:
    write_export(tmp_path, "lodge-export-2026-10-01_0900", [], [], [])
    newest = write_export(tmp_path, "lodge-export-2026-10-30_2130", [], [], [])
    write_export(tmp_path, "lodge-export-2026-10-31_0800", [], [], [], manifest=False)   # still being written
    assert ax.find_export(tmp_path) == newest


def test_empty_export_does_not_divide_by_zero(tmp_path: Path) -> None:
    s = ax.summarise(ax.load_export(write_export(tmp_path, "lodge-export-x", [], [], [])))
    assert (s["occupancy_pct"], s["adr_k"], s["no_show_rate_pct"]) == (0.0, 0.0, 0.0)


def test_unknown_format_is_refused(tmp_path: Path) -> None:
    folder = write_export(tmp_path, "lodge-export-x", [], [], [], version=2)
    with pytest.raises(ax.ExportError, match="format 2"):
        ax.load_export(folder)


def test_half_copied_file_is_noticed(export: Path) -> None:
    lines = (export / "bookings.csv").read_text(encoding="utf-8").splitlines()
    (export / "bookings.csv").write_text("\n".join(lines[:-2]) + "\n", encoding="utf-8")
    with pytest.raises(ax.ExportError, match="manifest says 5"):
        ax.load_export(export)


def test_command_line_exit_codes(export: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert ax.main([str(export), "--json"]) == 0
    assert json.loads(capsys.readouterr().out)["repeat_guests"] == 1
    assert ax.main([str(tmp_path / "nowhere")]) == 2

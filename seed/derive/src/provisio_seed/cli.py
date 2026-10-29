"""Derives the committed demand subset from the original Online Retail II archive (ADR 0025).

    uv run derive path/to/online_retail_ii.zip

The archive is downloaded by hand from the UCI Machine Learning Repository and never committed.
The outputs are deterministic: the same archive and the same rules give byte-identical files.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import zipfile
from pathlib import Path

import pandas as pd

from provisio_seed import rules

SOURCE = {
    "name": "Online Retail II",
    "url": "https://archive.ics.uci.edu/dataset/502/online+retail+ii",
    "license": "CC BY 4.0",
    "archive": "online_retail_ii.zip",
    # Recorded on the first download (28 October 2026); UCI publishes no checksum of its own.
    "sha256": "572e36277c2390fbfde10664750731e0a86f55e33470d91919085f0408e67bfb",
}
WORKBOOK = "online_retail_II.xlsx"
SHEETS = ("Year 2009-2010", "Year 2010-2011")

DERIVE_DIR = Path(__file__).resolve().parents[2]
SKU_LIST = DERIVE_DIR / "bags-skus.csv"
DEFAULT_OUT = DERIVE_DIR.parent / "data" / "online-retail-ii"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_workbook(archive: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    data = archive.read_bytes()
    if sha256(data) != SOURCE["sha256"]:
        raise SystemExit(f"{archive}: SHA-256 differs from the recorded one; refusing to derive from an unknown file")
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        workbook = zf.read(WORKBOOK)
    sheets = pd.read_excel(io.BytesIO(workbook), sheet_name=list(SHEETS), dtype={"Invoice": str, "StockCode": str})
    return sheets[SHEETS[0]], sheets[SHEETS[1]]


def read_sku_list(path: Path = SKU_LIST) -> pd.DataFrame:
    skus = pd.read_csv(path, dtype=str, keep_default_na=False)
    return skus[skus["included"] == "yes"][["sku", "description"]].reset_index(drop=True)


def to_csv(frame: pd.DataFrame) -> bytes:
    return frame.to_csv(index=False, lineterminator="\n").encode()


def derive(first: pd.DataFrame, second: pd.DataFrame, skus: pd.DataFrame) -> dict[str, bytes]:
    lines = rules.drop_partial_last_day(rules.merge_sheets(first, second))
    calendar = rules.opening_calendar(lines)
    selected = set(skus["sku"])
    result = rules.apply_cancellations(rules.product_lines(lines, selected))
    demand = rules.daily_demand(result.orders, calendar)
    rules.check_coverage(demand, selected)

    files = {
        "daily-demand.csv": to_csv(demand.assign(day=demand["day"].dt.strftime("%Y-%m-%d"))),
        "calendar.csv": to_csv(
            calendar.assign(day=calendar["day"].dt.strftime("%Y-%m-%d"), open=calendar["open"].map(str).str.lower())
        ),
        "skus.csv": to_csv(skus.sort_values("sku", ignore_index=True)),
    }
    manifest = {
        "dataset": "online-retail-ii",
        "source": SOURCE,
        "generator": "seed/derive",
        "periods": {p.name: {"start": p.start.isoformat(), "end": p.end.isoformat()} for p in rules.PERIODS},
        "summary": {
            "skus": len(selected),
            "calendarDays": len(calendar),
            "openDays": int(calendar["open"].sum()),
            "orderedQuantity": int(result.orders["Quantity"].sum() + result.matched_quantity),
            "cancelledQuantityMatched": result.matched_quantity,
            "cancelledQuantityUnmatched": result.unmatched_quantity,
            "demandQuantity": int(demand["quantity"].sum()),
        },
        "files": {
            name: {"sha256": sha256(content), "rows": content.count(b"\n") - 1}
            for name, content in sorted(files.items())
        },
    }
    files["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive", type=Path, help="online_retail_ii.zip as downloaded from UCI")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output directory (default: {DEFAULT_OUT})")
    args = parser.parse_args()

    first, second = read_workbook(args.archive)
    files = derive(first, second, read_sku_list())
    args.out.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        (args.out / name).write_bytes(content)
        print(f"wrote {args.out / name}")

"""Checks the committed subset against its manifest, so a hand edit or a stale file fails CI."""

import csv
import hashlib
import json

from provisio_seed.cli import DEFAULT_OUT, read_sku_list

MANIFEST = json.loads((DEFAULT_OUT / "manifest.json").read_text())


def rows(name):
    with (DEFAULT_OUT / name).open(newline="") as f:
        return list(csv.DictReader(f))


def test_files_match_the_manifest():
    for name, expected in MANIFEST["files"].items():
        content = (DEFAULT_OUT / name).read_bytes()
        assert hashlib.sha256(content).hexdigest() == expected["sha256"], name
        assert content.count(b"\n") - 1 == expected["rows"], name


def test_published_products_are_the_curated_list():
    products = rows("skus.csv")
    assert [r["sku"] for r in products] == sorted(read_sku_list()["sku"])
    assert list(products[0]) == ["sku", "description", "category", "unit"]
    assert {(r["category"], r["unit"]) for r in products} == {("BAGS", "EA")}


def test_demand_falls_on_open_days_and_is_positive():
    open_days = {r["day"] for r in rows("calendar.csv") if r["open"] == "true"}
    demand = rows("daily-demand.csv")
    assert all(r["day"] in open_days for r in demand)
    assert all(int(r["quantity"]) > 0 for r in demand)

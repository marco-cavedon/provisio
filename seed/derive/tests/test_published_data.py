"""Checks the committed subset against its manifest, so a hand edit or a stale file fails CI."""

import csv
import hashlib
import json

import pandas as pd
import pytest

from provisio_seed import cli, rules
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


def calendar_frame(name):
    frame = pd.DataFrame(rows(name))
    return frame.assign(day=pd.to_datetime(frame["day"]), open=frame["open"] == "true")


def test_manifest_is_what_derive_writes():
    # derive builds the manifest with cli.manifest; a hand edit of manifest.json, or of its summary, fails here
    files = {name: (DEFAULT_OUT / name).read_bytes() for name in MANIFEST["files"]}
    assert cli.manifest(MANIFEST["summary"], files) == (DEFAULT_OUT / "manifest.json").read_bytes()


def test_planned_calendar_follows_the_rules_from_the_day_after_the_observed_one():
    observed = calendar_frame("calendar.csv")
    assert (DEFAULT_OUT / "planned-calendar.csv").read_bytes() == cli.calendar_csv(rules.planned_calendar(observed))
    planned = calendar_frame("planned-calendar.csv")
    assert planned["day"].min() == observed["day"].max() + pd.Timedelta(days=1)
    assert planned["day"].max().date() == rules.PLANNED_CALENDAR_END
    assert MANIFEST["summary"]["plannedCalendarDays"] == len(planned)
    assert MANIFEST["summary"]["plannedOpenDays"] == int(planned["open"].sum())


@pytest.mark.parametrize("year", [2009, 2010])
def test_planning_rules_reproduce_the_observed_christmas_and_new_year(year):
    # The rules are learned from the data (ADR 0029): around both observed year ends they give the observed calendar
    observed = calendar_frame("calendar.csv")
    window = observed[observed["day"].between(f"{year}-12-10", f"{year + 1}-01-10")]
    assert list(window["open"]) == [not rules.planned_closed(day.date()) for day in window["day"]]


def test_store_is_closed_on_saturdays_but_once():
    observed = calendar_frame("calendar.csv")
    saturdays = observed[observed["day"].dt.day_of_week == rules.PLANNED_CLOSED_WEEKDAY]
    assert int(saturdays["open"].sum()) == 1

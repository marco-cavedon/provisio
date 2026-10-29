"""Demand derivation rules for Online Retail II (ADR 0025).

Every rule is a pure function on pandas data frames, so each can be tested on a small fixture.
The input columns are those of the original workbook: Invoice, StockCode, Description, Quantity,
InvoiceDate, Price, Customer ID, Country.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import pandas as pd

# The two worksheets overlap on 1-9 December 2010 with identical lines (ADR 0025).
SHEET_CUTOVER = pd.Timestamp("2010-12-01")

# The dataset ends at midday on this day: it is excluded, or it would look like a collapse in demand.
PARTIAL_LAST_DAY = pd.Timestamp("2011-12-09")

# Product codes are five digits, optionally followed by letters for variants (85099B).
# Everything else is postage, fees, manual entries, discounts, vouchers or test codes.
PRODUCT_CODE = r"^\d{5}[A-Za-z]*$"

# A cancellation matches an order placed on the same calendar day or the day before.
CANCELLATION_WINDOW_DAYS = 1


@dataclass(frozen=True)
class Period:
    name: str
    start: date
    end: date  # inclusive


PERIODS = (
    Period("fit", date(2009, 12, 1), date(2010, 12, 31)),
    Period("selection", date(2011, 1, 1), date(2011, 6, 30)),
    Period("replay", date(2011, 7, 1), date(2011, 12, 8)),
)


def merge_sheets(first: pd.DataFrame, second: pd.DataFrame) -> pd.DataFrame:
    """Joins the two worksheets without the overlap.

    No generic de-duplication: repeated lines inside one invoice can be legitimate.
    """
    return pd.concat(
        [first[first["InvoiceDate"] < SHEET_CUTOVER], second[second["InvoiceDate"] >= SHEET_CUTOVER]],
        ignore_index=True,
    )


def drop_partial_last_day(lines: pd.DataFrame) -> pd.DataFrame:
    last_day = lines["InvoiceDate"].max().normalize()
    if last_day != PARTIAL_LAST_DAY:
        raise ValueError(f"expected the data to end on {PARTIAL_LAST_DAY.date()}, it ends on {last_day.date()}")
    return lines[lines["InvoiceDate"] < PARTIAL_LAST_DAY]


def opening_calendar(lines: pd.DataFrame) -> pd.DataFrame:
    """One row per calendar day between the first and the last day: open if any transaction was recorded."""
    days = lines["InvoiceDate"].dt.normalize()
    open_days = set(days.unique())
    all_days = pd.date_range(days.min(), days.max(), freq="D")
    return pd.DataFrame({"day": all_days, "open": [d in open_days for d in all_days]})


def product_lines(lines: pd.DataFrame, skus: set[str]) -> pd.DataFrame:
    """Lines of the selected products with a positive price, cancellations included."""
    code = lines["StockCode"].astype(str)
    return lines[code.str.match(PRODUCT_CODE) & code.isin(skus) & (lines["Price"] > 0)]


@dataclass(frozen=True)
class CancellationResult:
    orders: pd.DataFrame  # order lines that remain demand
    matched_quantity: int  # cancelled quantity removed from demand
    unmatched_quantity: int  # cancelled quantity treated as returns, demand unchanged


def apply_cancellations(lines: pd.DataFrame) -> CancellationResult:
    """Removes the demand of orders cancelled shortly after being placed.

    A cancellation (invoice prefixed with C) matches an order line with the same customer, product and quantity,
    placed at or before the cancellation and at most CANCELLATION_WINDOW_DAYS calendar days earlier. Among several
    candidates the most recent unmatched one is chosen. Unmatched cancellations are returns of older sales, or lack a
    customer to match on: they do not reduce demand. Negative quantities outside cancellations are stock adjustments
    and are not demand either.
    """
    invoice = lines["Invoice"].astype(str)
    orders = lines[invoice.str[0].str.isdigit() & (lines["Quantity"] > 0)]
    cancellations = lines[invoice.str.startswith("C")].sort_values("InvoiceDate", kind="stable")

    candidates: dict[tuple, list] = {}
    for index, row in orders.sort_values("InvoiceDate", kind="stable").iterrows():
        if pd.notna(row["Customer ID"]):
            key = (row["Customer ID"], str(row["StockCode"]), int(row["Quantity"]))
            candidates.setdefault(key, []).append((row["InvoiceDate"], index))

    matched_indexes: set = set()
    matched_quantity = unmatched_quantity = 0
    window = pd.Timedelta(days=CANCELLATION_WINDOW_DAYS)
    for _, row in cancellations.iterrows():
        quantity = -int(row["Quantity"])
        key = (row["Customer ID"], str(row["StockCode"]), quantity)
        found = None
        if pd.notna(row["Customer ID"]):
            cancelled_on = row["InvoiceDate"].normalize()
            for placed_at, index in reversed(candidates.get(key, [])):
                if index in matched_indexes or placed_at > row["InvoiceDate"]:
                    continue
                if cancelled_on - placed_at.normalize() > window:
                    break  # candidates are in time order: every earlier one is outside the window too
                found = index
                break
        if found is None:
            unmatched_quantity += quantity
        else:
            matched_indexes.add(found)
            matched_quantity += quantity

    return CancellationResult(orders.drop(index=list(matched_indexes)), matched_quantity, unmatched_quantity)


def daily_demand(orders: pd.DataFrame, calendar: pd.DataFrame) -> pd.DataFrame:
    """Demand per product and day, on days with sales only: zeros are implied by the opening calendar."""
    demand = (
        orders.assign(sku=orders["StockCode"].astype(str), day=orders["InvoiceDate"].dt.normalize())
        .groupby(["sku", "day"], as_index=False)["Quantity"]
        .sum()
        .rename(columns={"Quantity": "quantity"})
        .sort_values(["sku", "day"], ignore_index=True)
    )
    closed = set(calendar.loc[~calendar["open"], "day"])
    if demand["day"].isin(closed).any():
        raise ValueError("demand recorded on a closed day: the calendar and the demand disagree")
    return demand


def check_coverage(demand: pd.DataFrame, skus: set[str]) -> None:
    """Every selected product needs demand in each period (ADR 0025): fit, selection and replay."""
    problems = []
    for sku in sorted(skus):
        days = demand.loc[demand["sku"] == sku, "day"].dt.date
        for period in PERIODS:
            if not days.between(period.start, period.end).any():
                problems.append(f"{sku}: no demand in the {period.name} period")
    if problems:
        raise ValueError("selected products without full coverage:\n" + "\n".join(problems))

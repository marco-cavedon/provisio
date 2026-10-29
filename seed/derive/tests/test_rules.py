import pandas as pd
import pytest

from provisio_seed import rules


def lines(*rows):
    """Builds invoice lines from (invoice, sku, quantity, timestamp, customer) tuples, price 1."""
    return pd.DataFrame(
        [
            {
                "Invoice": invoice,
                "StockCode": sku,
                "Description": "",
                "Quantity": quantity,
                "InvoiceDate": pd.Timestamp(at),
                "Price": 1.0,
                "Customer ID": customer,
                "Country": "United Kingdom",
            }
            for invoice, sku, quantity, at, customer in rows
        ],
        columns=["Invoice", "StockCode", "Description", "Quantity", "InvoiceDate", "Price", "Customer ID", "Country"],
    )


class TestMergeSheets:
    def test_overlap_is_taken_from_the_second_sheet_only(self):
        first = lines(("1", "20725", 1, "2010-11-30 10:00", 1.0), ("2", "20725", 2, "2010-12-01 10:00", 1.0))
        second = lines(("2", "20725", 2, "2010-12-01 10:00", 1.0), ("3", "20725", 3, "2010-12-02 10:00", 1.0))
        merged = rules.merge_sheets(first, second)
        assert list(merged["Invoice"]) == ["1", "2", "3"]

    def test_repeated_lines_inside_a_sheet_are_kept(self):
        first = lines(("1", "20725", 1, "2010-11-30 10:00", 1.0), ("1", "20725", 1, "2010-11-30 10:00", 1.0))
        assert len(rules.merge_sheets(first, lines())) == 2


class TestPartialLastDay:
    def test_last_day_is_dropped(self):
        data = lines(("1", "20725", 1, "2011-12-08 10:00", 1.0), ("2", "20725", 1, "2011-12-09 09:00", 1.0))
        assert list(rules.drop_partial_last_day(data)["Invoice"]) == ["1"]

    def test_unexpected_end_date_is_refused(self):
        with pytest.raises(ValueError, match="ends on 2011-12-10"):
            rules.drop_partial_last_day(lines(("1", "20725", 1, "2011-12-10 09:00", 1.0)))


class TestOpeningCalendar:
    def test_days_without_any_transaction_are_closed(self):
        data = lines(("1", "POST", 1, "2011-01-07 10:00", 1.0), ("2", "20725", 1, "2011-01-09 10:00", 1.0))
        calendar = rules.opening_calendar(data)
        assert list(calendar["day"].dt.strftime("%m-%d")) == ["01-07", "01-08", "01-09"]
        assert list(calendar["open"]) == [True, False, True]


class TestProductLines:
    def test_non_product_codes_other_products_and_free_lines_are_excluded(self):
        data = lines(
            ("1", "85099B", 1, "2011-01-07", 1.0),
            ("2", "POST", 1, "2011-01-07", 1.0),
            ("3", "DCGS0058", 1, "2011-01-07", 1.0),
            ("4", "20725", 1, "2011-01-07", 1.0),
            ("5", "85099B", 1, "2011-01-07", 1.0),
        )
        data.loc[4, "Price"] = 0.0
        assert list(rules.product_lines(data, {"85099B", "POST", "DCGS0058"})["Invoice"]) == ["1"]


class TestCancellations:
    def test_same_day_cancellation_removes_the_order(self):
        data = lines(("1", "20725", 100, "2011-01-07 09:00", 7.0), ("C2", "20725", -100, "2011-01-07 09:10", 7.0))
        result = rules.apply_cancellations(data)
        assert result.orders.empty
        assert (result.matched_quantity, result.unmatched_quantity) == (100, 0)

    def test_next_day_cancellation_still_matches(self):
        data = lines(("1", "20725", 100, "2011-01-07 23:00", 7.0), ("C2", "20725", -100, "2011-01-08 08:00", 7.0))
        assert rules.apply_cancellations(data).orders.empty

    def test_cancellation_two_days_later_is_a_return(self):
        data = lines(("1", "20725", 100, "2011-01-07 09:00", 7.0), ("C2", "20725", -100, "2011-01-09 09:00", 7.0))
        result = rules.apply_cancellations(data)
        assert list(result.orders["Invoice"]) == ["1"]
        assert (result.matched_quantity, result.unmatched_quantity) == (0, 100)

    @pytest.mark.parametrize(
        "cancellation",
        [
            ("C2", "20725", -50, "2011-01-07 10:00", 7.0),  # different quantity
            ("C2", "20725", -100, "2011-01-07 10:00", 8.0),  # different customer
            ("C2", "20726", -100, "2011-01-07 10:00", 7.0),  # different product
            ("C2", "20725", -100, "2011-01-07 08:00", 7.0),  # before the order
        ],
    )
    def test_cancellation_that_differs_does_not_match(self, cancellation):
        data = lines(("1", "20725", 100, "2011-01-07 09:00", 7.0), cancellation)
        assert list(rules.apply_cancellations(data).orders["Invoice"]) == ["1"]

    def test_cancellation_without_customer_does_not_match(self):
        data = lines(
            ("1", "20725", 100, "2011-01-07 09:00", float("nan")),
            ("C2", "20725", -100, "2011-01-07 09:10", float("nan")),
        )
        assert list(rules.apply_cancellations(data).orders["Invoice"]) == ["1"]

    def test_most_recent_unmatched_order_is_chosen(self):
        data = lines(
            ("1", "20725", 10, "2011-01-07 09:00", 7.0),
            ("2", "20725", 10, "2011-01-07 11:00", 7.0),
            ("C3", "20725", -10, "2011-01-07 12:00", 7.0),
            ("C4", "20725", -10, "2011-01-07 13:00", 7.0),
            ("C5", "20725", -10, "2011-01-07 14:00", 7.0),
        )
        result = rules.apply_cancellations(data)
        assert result.orders.empty
        assert (result.matched_quantity, result.unmatched_quantity) == (20, 10)

    def test_adjustments_and_negative_lines_are_not_demand(self):
        data = lines(("1", "20725", 10, "2011-01-07 09:00", 7.0), ("2", "20725", -3, "2011-01-07 10:00", None))
        data = pd.concat([data, lines(("A3", "20725", 5, "2011-01-07 11:00", None))], ignore_index=True)
        assert list(rules.apply_cancellations(data).orders["Invoice"]) == ["1"]


class TestDailyDemand:
    calendar = pd.DataFrame(
        {"day": pd.to_datetime(["2011-01-07", "2011-01-08", "2011-01-09"]), "open": [True, False, True]}
    )

    def test_quantities_are_summed_per_product_and_day(self):
        orders = lines(
            ("1", "20725", 2, "2011-01-07 09:00", 1.0),
            ("2", "20725", 3, "2011-01-07 15:00", 2.0),
            ("3", "20725", 4, "2011-01-09 09:00", 1.0),
        )
        demand = rules.daily_demand(orders, self.calendar)
        assert demand.to_dict("list") == {
            "sku": ["20725", "20725"],
            "day": list(pd.to_datetime(["2011-01-07", "2011-01-09"])),
            "quantity": [5, 4],
        }

    def test_demand_on_a_closed_day_is_refused(self):
        with pytest.raises(ValueError, match="closed day"):
            rules.daily_demand(lines(("1", "20725", 2, "2011-01-08 09:00", 1.0)), self.calendar)


class TestCoverage:
    def test_product_missing_a_period_is_refused(self):
        demand = pd.DataFrame(
            {"sku": ["20725", "20725"], "day": pd.to_datetime(["2010-03-01", "2011-08-01"]), "quantity": [1, 1]}
        )
        with pytest.raises(ValueError, match="20725: no demand in the selection period"):
            rules.check_coverage(demand, {"20725"})

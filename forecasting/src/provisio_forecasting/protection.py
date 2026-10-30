"""The protection interval as the contract defines it (ADR 0028)."""

import math
from datetime import date, timedelta

from provisio_forecasting.api.models import SeriesInput


def protection_interval_end(as_of: date, series: SeriesInput) -> date:
    """Last day, included, of the interval a replenishment decision taken on `as_of` must cover.

    The interval starts on `as_of` and lasts `ceil(leadTime.meanDays) + reviewPeriodDays` calendar days: with periodic
    review, an order placed today must last until the order placed at the next review arrives. Lead time variability
    belongs in the quantile, not in this nominal end.
    """
    length = math.ceil(series.lead_time.mean_days) + series.review_period_days
    return as_of + timedelta(days=length - 1)

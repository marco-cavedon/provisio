"""Business calendar of a request: which days are open (ADR 0025, 0028)."""

from collections.abc import Iterator
from datetime import date, timedelta

from provisio_forecasting.api.models import BusinessCalendar


class OpenDays:
    """Every day from `from` to `to`, both included, is open unless it is closed.

    Demand exists on open days only: a closed day is not a day of zero demand.
    """

    def __init__(self, calendar: BusinessCalendar) -> None:
        self.first = calendar.from_
        self.last = calendar.to
        self._closed = frozenset(calendar.closed_days)

    def covers(self, day: date) -> bool:
        return self.first <= day <= self.last

    def is_open(self, day: date) -> bool:
        return self.covers(day) and day not in self._closed

    def between(self, start: date, end_exclusive: date) -> Iterator[date]:
        """Open days from `start` included to `end_exclusive` excluded, in order."""
        day = start
        while day < end_exclusive:
            if self.is_open(day):
                yield day
            day += timedelta(days=1)

    def count(self, start: date, end_exclusive: date) -> int:
        return sum(1 for _ in self.between(start, end_exclusive))

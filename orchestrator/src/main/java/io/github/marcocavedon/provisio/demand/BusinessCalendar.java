package io.github.marcocavedon.provisio.demand;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The days the store was trading, between two dates (ADR 0025).
 *
 * <p>Every day from {@code from} to {@code to}, both included, is open unless it is in {@code closedDays}. A closed day
 * is not a day with zero demand: the store was not trading. Days outside the range are unknown.
 *
 * @param from first day covered
 * @param to last day covered, included
 * @param closedDays days in the range on which the store was closed
 */
public record BusinessCalendar(LocalDate from, LocalDate to, Set<LocalDate> closedDays) {

  public BusinessCalendar {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("Calendar ends (" + to + ") before it starts (" + from + ")");
    }
    closedDays = Set.copyOf(closedDays);
    for (LocalDate day : closedDays) {
      if (day.isBefore(from) || day.isAfter(to)) {
        throw new IllegalArgumentException("Closed day " + day + " is outside the calendar " + from + ".." + to);
      }
    }
  }

  /** Whether the day is in the calendar's range. */
  public boolean covers(LocalDate day) {
    return !day.isBefore(from) && !day.isAfter(to);
  }

  /** Whether the store was trading on that day; false outside the range. */
  public boolean isOpen(LocalDate day) {
    return covers(day) && !closedDays.contains(day);
  }

  /** Open days from {@code start} included to {@code endExclusive} excluded, in order. */
  public Stream<LocalDate> openDays(LocalDate start, LocalDate endExclusive) {
    return start.datesUntil(endExclusive).filter(this::isOpen);
  }
}

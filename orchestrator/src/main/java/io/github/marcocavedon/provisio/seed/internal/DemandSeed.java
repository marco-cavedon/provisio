package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The demand part of a dataset: {@code calendar.csv}, {@code planned-calendar.csv} and {@code daily-demand.csv}, as
 * written by {@code seed/derive}.
 *
 * <p>The observed calendar comes from the sales data; the planned one continues it from the next day (ADR 0029).
 * Parsed with {@link SeedManifest#csvRows}: anything unexpected is an error, not something to recover from.
 */
record DemandSeed(BusinessCalendar calendar, List<DemandEntry> entries) {

  static final String CALENDAR = "calendar.csv";
  static final String PLANNED_CALENDAR = "planned-calendar.csv";
  static final String DAILY_DEMAND = "daily-demand.csv";

  static DemandSeed from(SeedManifest manifest) {
    Set<LocalDate> closed = new HashSet<>();
    Range observed = readCalendar(manifest, CALENDAR, closed);
    Range planned = readCalendar(manifest, PLANNED_CALENDAR, closed);
    if (!planned.from().equals(observed.to().plusDays(1))) {
      throw new IllegalStateException(PLANNED_CALENDAR + " must start on " + observed.to().plusDays(1)
          + ", the day after " + CALENDAR + " ends; it starts on " + planned.from());
    }
    BusinessCalendar calendar = new BusinessCalendar(observed.from(), observed.to(), planned.to(), closed);

    List<DemandEntry> entries = new ArrayList<>();
    for (String[] row : manifest.csvRows(DAILY_DEMAND, "sku,day,quantity")) {
      entries.add(new DemandEntry(row[0], LocalDate.parse(row[1]), Integer.parseInt(row[2])));
    }
    return new DemandSeed(calendar, List.copyOf(entries));
  }

  /** Reads one calendar file into {@code closed} and returns its range, after checking it lists every day in order. */
  private static Range readCalendar(SeedManifest manifest, String name, Set<LocalDate> closed) {
    List<String[]> days = manifest.csvRows(name, "day,open");
    if (days.isEmpty()) {
      throw new IllegalStateException(name + " is empty");
    }
    for (String[] row : days) {
      switch (row[1]) {
        case "true" -> { }
        case "false" -> closed.add(LocalDate.parse(row[0]));
        default -> throw new IllegalStateException(name + ": unexpected value of open: " + row[1]);
      }
    }
    LocalDate from = LocalDate.parse(days.getFirst()[0]);
    LocalDate to = LocalDate.parse(days.getLast()[0]);
    if (from.datesUntil(to.plusDays(1)).count() != days.size()) {
      throw new IllegalStateException(name + " must list every day from " + from + " to " + to + ", in order");
    }
    return new Range(from, to);
  }

  int rows() {
    return (int) (calendar.from().datesUntil(calendar.to().plusDays(1)).count() + entries.size());
  }

  private record Range(LocalDate from, LocalDate to) {
  }
}

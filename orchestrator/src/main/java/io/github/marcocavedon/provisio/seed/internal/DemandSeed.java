package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The demand part of a dataset: {@code calendar.csv} and {@code daily-demand.csv}, as written by {@code seed/derive}.
 *
 * <p>Parsed with {@link SeedManifest#csvRows}: anything unexpected is an error, not something to recover from.
 */
record DemandSeed(BusinessCalendar calendar, List<DemandEntry> entries) {

  static final String CALENDAR = "calendar.csv";
  static final String DAILY_DEMAND = "daily-demand.csv";

  static DemandSeed from(SeedManifest manifest) {
    List<String[]> days = manifest.csvRows(CALENDAR, "day,open");
    if (days.isEmpty()) {
      throw new IllegalStateException(CALENDAR + " is empty");
    }
    Set<LocalDate> closed = new HashSet<>();
    for (String[] row : days) {
      switch (row[1]) {
        case "true" -> { }
        case "false" -> closed.add(LocalDate.parse(row[0]));
        default -> throw new IllegalStateException(CALENDAR + ": unexpected value of open: " + row[1]);
      }
    }
    LocalDate from = LocalDate.parse(days.getFirst()[0]);
    LocalDate to = LocalDate.parse(days.getLast()[0]);
    if (from.datesUntil(to.plusDays(1)).count() != days.size()) {
      throw new IllegalStateException(CALENDAR + " must list every day from " + from + " to " + to + ", in order");
    }
    BusinessCalendar calendar = new BusinessCalendar(from, to, closed);

    List<DemandEntry> entries = new ArrayList<>();
    for (String[] row : manifest.csvRows(DAILY_DEMAND, "sku,day,quantity")) {
      entries.add(new DemandEntry(row[0], LocalDate.parse(row[1]), Integer.parseInt(row[2])));
    }
    return new DemandSeed(calendar, List.copyOf(entries));
  }

  int rows() {
    return (int) (calendar.from().datesUntil(calendar.to().plusDays(1)).count() + entries.size());
  }
}

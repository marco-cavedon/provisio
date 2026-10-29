package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The demand part of a dataset: {@code calendar.csv} and {@code daily-demand.csv}, as written by {@code seed/derive}.
 *
 * <p>The files are machine-written with a fixed header and no quoting, and their checksums have been verified, so a
 * plain split is enough; anything unexpected is an error, not something to recover from.
 */
record DemandSeed(BusinessCalendar calendar, List<DemandEntry> entries) {

  static final String CALENDAR = "calendar.csv";
  static final String DAILY_DEMAND = "daily-demand.csv";

  static DemandSeed from(SeedManifest manifest) {
    List<String[]> days = rows(manifest, CALENDAR, "day,open");
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
    for (String[] row : rows(manifest, DAILY_DEMAND, "sku,day,quantity")) {
      entries.add(new DemandEntry(row[0], LocalDate.parse(row[1]), Integer.parseInt(row[2])));
    }
    return new DemandSeed(calendar, List.copyOf(entries));
  }

  int rows() {
    return (int) (calendar.from().datesUntil(calendar.to().plusDays(1)).count() + entries.size());
  }

  private static List<String[]> rows(SeedManifest manifest, String file, String header) {
    String[] lines = new String(manifest.file(file), StandardCharsets.UTF_8).split("\n");
    if (!lines[0].equals(header)) {
      throw new IllegalStateException(file + ": expected header '" + header + "', found '" + lines[0] + "'");
    }
    int columns = header.split(",").length;
    List<String[]> rows = new ArrayList<>(lines.length - 1);
    for (int i = 1; i < lines.length; i++) {
      String[] row = lines[i].split(",", -1);
      if (row.length != columns) {
        throw new IllegalStateException(file + " line " + (i + 1) + ": expected " + columns + " columns");
      }
      rows.add(row);
    }
    return rows;
  }
}

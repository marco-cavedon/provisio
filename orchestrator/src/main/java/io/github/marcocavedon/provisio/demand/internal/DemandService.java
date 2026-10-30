package io.github.marcocavedon.provisio.demand.internal;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.demand.DemandQueries;
import io.github.marcocavedon.provisio.demand.internal.DemandBucketDocument.DaySales;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service of the demand module: the only writer of {@code demand_history} and {@code business_calendar}
 * (ADR 0026).
 */
@Service
class DemandService implements DemandQueries, DemandImport {

  private final MongoTemplate mongoTemplate;
  private final TransactionTemplate transaction;

  DemandService(MongoTemplate mongoTemplate, PlatformTransactionManager transactionManager) {
    this.mongoTemplate = mongoTemplate;
    // Default propagation REQUIRED: joins the caller's transaction, so the seed can record the load atomically
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public Optional<BusinessCalendar> calendar() {
    List<CalendarYearDocument> years =
        mongoTemplate.find(new Query().with(Sort.by("_id")), CalendarYearDocument.class);
    if (years.isEmpty()) {
      return Optional.empty();
    }
    Set<LocalDate> closed = new HashSet<>();
    years.forEach(year -> closed.addAll(year.closedDays()));
    return Optional.of(new BusinessCalendar(years.getFirst().from(), years.getLast().to(), closed));
  }

  @Override
  public List<DailyDemand> dailyDemand(String sku, LocalDate from, LocalDate endExclusive) {
    Objects.requireNonNull(sku, "sku");
    if (endExclusive.isBefore(from)) {
      throw new IllegalArgumentException("Range ends (" + endExclusive + ") before it starts (" + from + ")");
    }
    BusinessCalendar calendar = calendar()
        .orElseThrow(() -> new IllegalStateException("No demand history loaded: the seed has not run"));
    if (endExclusive.isAfter(from)
        && !(calendar.covers(from) && calendar.covers(endExclusive.minusDays(1)))) {
      throw new IllegalArgumentException("Range " + from + ".." + endExclusive + " (end excluded) is outside the "
          + "calendar " + calendar.from() + ".." + calendar.to());
    }

    // Buckets whose month overlaps the range: from the month of `from` to the last month starting before the end
    Query buckets = query(where("sku").is(sku)
        .and("month").gte(from.withDayOfMonth(1)).lt(endExclusive))
        .with(Sort.by("month"));
    Map<LocalDate, Integer> sold = new HashMap<>();
    for (DemandBucketDocument bucket : mongoTemplate.find(buckets, DemandBucketDocument.class)) {
      bucket.days().forEach(day -> sold.put(day.day(), day.quantity()));
    }
    return calendar.openDays(from, endExclusive)
        .map(day -> new DailyDemand(day, sold.getOrDefault(day, 0)))
        .toList();
  }

  @Override
  public void replaceAll(BusinessCalendar calendar, List<DemandEntry> entries) {
    Objects.requireNonNull(calendar, "calendar");
    validate(calendar, entries);
    List<DemandBucketDocument> buckets = toBuckets(entries);
    List<CalendarYearDocument> years = toYears(calendar);

    transaction.executeWithoutResult(status -> {
      mongoTemplate.remove(new Query(), DemandBucketDocument.class);
      mongoTemplate.remove(new Query(), CalendarYearDocument.class);
      if (!buckets.isEmpty()) {
        mongoTemplate.insert(buckets, DemandBucketDocument.class);
      }
      mongoTemplate.insert(years, CalendarYearDocument.class);
    });
  }

  private static void validate(BusinessCalendar calendar, List<DemandEntry> entries) {
    Set<DemandKey> seen = new HashSet<>();
    for (DemandEntry entry : entries) {
      if (entry.quantity() <= 0) {
        throw new IllegalArgumentException("Quantity must be positive: " + entry);
      }
      if (!calendar.isOpen(entry.day())) {
        throw new IllegalArgumentException("Demand on a day that is not open in the calendar: " + entry);
      }
      if (!seen.add(new DemandKey(entry.sku(), entry.day()))) {
        throw new IllegalArgumentException("Repeated product and day: " + entry);
      }
    }
  }

  private static List<DemandBucketDocument> toBuckets(List<DemandEntry> entries) {
    Map<String, Map<YearMonth, List<DaySales>>> bySkuAndMonth = new TreeMap<>();
    for (DemandEntry entry : entries) {
      bySkuAndMonth.computeIfAbsent(entry.sku(), sku -> new TreeMap<>())
          .computeIfAbsent(YearMonth.from(entry.day()), month -> new ArrayList<>())
          .add(new DaySales(entry.day(), entry.quantity()));
    }
    List<DemandBucketDocument> buckets = new ArrayList<>();
    bySkuAndMonth.forEach((sku, months) -> months.forEach((month, days) -> {
      days.sort(Comparator.comparing(DaySales::day));
      buckets.add(new DemandBucketDocument(new ObjectId(), sku, month.atDay(1), List.copyOf(days)));
    }));
    return buckets;
  }

  private static List<CalendarYearDocument> toYears(BusinessCalendar calendar) {
    List<CalendarYearDocument> years = new ArrayList<>();
    for (int year = calendar.from().getYear(); year <= calendar.to().getYear(); year++) {
      LocalDate from = max(calendar.from(), LocalDate.of(year, 1, 1));
      LocalDate to = min(calendar.to(), LocalDate.of(year, 12, 31));
      List<LocalDate> closed = calendar.closedDays().stream()
          .filter(day -> !day.isBefore(from) && !day.isAfter(to))
          .sorted()
          .toList();
      years.add(new CalendarYearDocument(year, from, to, closed));
    }
    return years;
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  private record DemandKey(String sku, LocalDate day) {
  }
}

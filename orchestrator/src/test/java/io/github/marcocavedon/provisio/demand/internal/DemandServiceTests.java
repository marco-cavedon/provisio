package io.github.marcocavedon.provisio.demand.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Demand history in monthly buckets, calendar per year, zero-filled series (ADR 0026). Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class DemandServiceTests {

  // 2010-12-30 to 2011-02-02; Saturday 1 January and Sunday 2 January closed
  private static final LocalDate FROM = LocalDate.of(2010, 12, 30);
  private static final LocalDate TO = LocalDate.of(2011, 2, 2);
  private static final LocalDate NEW_YEAR = LocalDate.of(2011, 1, 1);
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(FROM, TO, Set.of(NEW_YEAR, LocalDate.of(2011, 1, 2)));

  @Autowired
  private DemandService demand;

  @Autowired
  private DemandIndexes indexes;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    mongoTemplate.remove(new Query(), DemandBucketDocument.class);
    mongoTemplate.remove(new Query(), CalendarYearDocument.class);
  }

  @Test
  void storesOneBucketPerProductAndMonthWithDaysInOrder() {
    demand.replaceAll(CALENDAR, List.of(
        entry("A", "2011-01-20", 5), entry("A", "2010-12-30", 3),
        entry("A", "2011-01-03", 7), entry("B", "2011-01-03", 1)));

    List<Document> buckets = raw(DemandBucketDocument.COLLECTION);
    assertThat(buckets).hasSize(3);
    Document januaryOfA = buckets.stream()
        .filter(b -> b.getString("sku").equals("A") && b.getDate("month").equals(midnightUtc("2011-01-01")))
        .findFirst().orElseThrow();
    assertThat(januaryOfA.getList("days", Document.class))
        .extracting(d -> d.getDate("day"), d -> d.getInteger("quantity"))
        .containsExactly(
            tuple(midnightUtc("2011-01-03"), 7),
            tuple(midnightUtc("2011-01-20"), 5));
  }

  @Test
  void storesTheCalendarOneDocumentPerYear() {
    demand.replaceAll(CALENDAR, List.of());

    List<Document> years = raw(CalendarYearDocument.COLLECTION);
    assertThat(years).extracting(y -> y.getInteger("_id")).containsExactly(2010, 2011);
    Document year2010 = years.getFirst();
    assertThat(year2010.getDate("from")).isEqualTo(midnightUtc("2010-12-30"));
    assertThat(year2010.getDate("to")).isEqualTo(midnightUtc("2010-12-31"));
    assertThat(year2010.getList("closedDays", Date.class)).isEmpty();
    assertThat(years.get(1).getList("closedDays", Date.class))
        .containsExactly(midnightUtc("2011-01-01"), midnightUtc("2011-01-02"));

    assertThat(demand.calendar()).contains(CALENDAR);
  }

  @Test
  void seriesHasOneEntryPerOpenDayWithZerosAndNoClosedDays() {
    demand.replaceAll(CALENDAR, List.of(entry("A", "2010-12-31", 4), entry("A", "2011-01-04", 2)));

    assertThat(demand.dailyDemand("A", FROM, LocalDate.of(2011, 1, 5))).containsExactly(
        new DailyDemand(LocalDate.of(2010, 12, 30), 0),
        new DailyDemand(LocalDate.of(2010, 12, 31), 4),
        new DailyDemand(LocalDate.of(2011, 1, 3), 0),
        new DailyDemand(LocalDate.of(2011, 1, 4), 2));
  }

  @Test
  void seriesExcludesTheEndDayAndDaysOutsideTheRange() {
    demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-03", 1), entry("A", "2011-01-04", 2),
        entry("A", "2011-01-05", 3)));

    assertThat(demand.dailyDemand("A", LocalDate.of(2011, 1, 4), LocalDate.of(2011, 1, 5)))
        .containsExactly(new DailyDemand(LocalDate.of(2011, 1, 4), 2));
    assertThat(demand.dailyDemand("A", LocalDate.of(2011, 1, 4), LocalDate.of(2011, 1, 4))).isEmpty();
  }

  @Test
  void productWithoutHistoryGetsZeros() {
    demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-03", 1)));

    assertThat(demand.dailyDemand("UNKNOWN", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 5)))
        .extracting(DailyDemand::quantity).containsExactly(0, 0);
  }

  @Test
  void seriesOutsideTheCalendarOrWithoutHistoryFails() {
    assertThatThrownBy(() -> demand.dailyDemand("A", FROM, TO))
        .isInstanceOf(IllegalStateException.class);

    demand.replaceAll(CALENDAR, List.of());
    assertThatThrownBy(() -> demand.dailyDemand("A", FROM.minusDays(1), TO))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the calendar");
    assertThatThrownBy(() -> demand.dailyDemand("A", FROM, TO.plusDays(2)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the calendar");
    assertThatCode(() -> demand.dailyDemand("A", FROM, TO.plusDays(1))).doesNotThrowAnyException();
  }

  @Test
  void invalidEntriesAreRefusedAndNothingChanges() {
    demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-03", 1)));

    assertThatThrownBy(() -> demand.replaceAll(CALENDAR, List.of(entry("A", NEW_YEAR.toString(), 1))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not open");
    assertThatThrownBy(() -> demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-04", 0))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    assertThatThrownBy(() -> demand.replaceAll(CALENDAR,
        List.of(entry("A", "2011-01-04", 1), entry("A", "2011-01-04", 2))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Repeated");

    assertThat(demand.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 4)))
        .containsExactly(new DailyDemand(LocalDate.of(2011, 1, 3), 1));
  }

  @Test
  void replaceAllRemovesWhatWasThereBefore() {
    demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-03", 1), entry("B", "2011-01-03", 1)));
    BusinessCalendar shorter = new BusinessCalendar(LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 31), Set.of());
    demand.replaceAll(shorter, List.of(entry("A", "2011-01-04", 9)));

    assertThat(raw(DemandBucketDocument.COLLECTION)).hasSize(1);
    assertThat(raw(CalendarYearDocument.COLLECTION)).hasSize(1);
    assertThat(demand.calendar()).contains(shorter);
  }

  @Test
  void daysStayTheSameWhateverTheJvmTimeZone() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
      demand.replaceAll(CALENDAR, List.of(entry("A", "2011-01-03", 6)));
      assertThat(raw(DemandBucketDocument.COLLECTION).getFirst().getDate("month"))
          .isEqualTo(midnightUtc("2011-01-01"));

      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      assertThat(demand.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 4)))
          .containsExactly(new DailyDemand(LocalDate.of(2011, 1, 3), 6));
      assertThat(demand.calendar()).contains(CALENDAR);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void createsTheUniqueBucketIndexIdempotently() {
    Document index = mongoTemplate.getCollection(DemandBucketDocument.COLLECTION).listIndexes()
        .into(new ArrayList<>()).stream()
        .filter(i -> DemandIndexes.BUCKETS_BY_SKU_AND_MONTH.equals(i.getString("name")))
        .findFirst().orElseThrow();
    assertThat(index.get("key", Document.class)).isEqualTo(new Document("sku", 1).append("month", 1));
    assertThat(index.getBoolean("unique")).isTrue();
    assertThatCode(indexes::createIndexes).doesNotThrowAnyException();
  }

  private static DemandEntry entry(String sku, String day, int quantity) {
    return new DemandEntry(sku, LocalDate.parse(day), quantity);
  }

  private static Date midnightUtc(String day) {
    return Date.from(Instant.parse(day + "T00:00:00Z"));
  }

  private List<Document> raw(String collection) {
    return mongoTemplate.getCollection(collection).find().sort(new Document("_id", 1)).into(new ArrayList<>());
  }
}

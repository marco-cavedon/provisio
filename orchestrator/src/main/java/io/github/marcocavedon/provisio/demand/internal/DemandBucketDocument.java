package io.github.marcocavedon.provisio.demand.internal;

import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code demand_history}: one bucket per product and month, days with sales only (ADR 0026).
 *
 * <p>Bounded by construction: at most 31 entries. Every read of demand is a series, so a series is a few dozen of
 * these documents, read through the unique {@code sku_month_unique} index.
 *
 * @param month first day of the month
 * @param days days with sales, ordered by day, quantities strictly positive
 */
@Document(collection = DemandBucketDocument.COLLECTION)
public record DemandBucketDocument(@Id ObjectId id, String sku, LocalDate month, List<DaySales> days) {

  public static final String COLLECTION = "demand_history";

  /** Units sold on one day. */
  public record DaySales(LocalDate day, int quantity) {
  }
}

package io.github.marcocavedon.provisio.forecasting.internal;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ForecastQueries;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Read side of the forecasting module. The only writer of {@code forecasts} is {@link ForecastBatch}.
 */
@Service
class ForecastService implements ForecastQueries {

  private final MongoTemplate mongoTemplate;

  ForecastService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public Optional<Forecast> findForecast(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(mongoTemplate.findById(id, ForecastDocument.class)).map(ForecastDocument::toForecast);
  }

  @Override
  public Optional<Forecast> latestForecast(String sku, LocalDate asOf) {
    Objects.requireNonNull(sku, "sku");
    Objects.requireNonNull(asOf, "asOf");
    // Served by the sku_asOf_computedAt index: equality on sku, then both sort keys in index order
    var latest = query(where("sku").is(sku).and("asOf").lte(asOf))
        .with(Sort.by(Direction.DESC, "asOf", "computedAt"))
        .limit(1);
    return Optional.ofNullable(mongoTemplate.findOne(latest, ForecastDocument.class)).map(ForecastDocument::toForecast);
  }
}

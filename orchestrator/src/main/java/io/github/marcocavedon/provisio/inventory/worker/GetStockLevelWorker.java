package io.github.marcocavedon.provisio.inventory.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Job worker {@value #JOB_TYPE}: on-hand stock of one SKU (ADR 0022, ADR 0019).
 *
 * <p>It returns a domain variable and does not know whether an AI agent or a plain process calls it: the BPMN maps
 * {@code stockLevel} to {@code toolCallResult} where needed. Contract of the single output variable
 * {@code stockLevel}:
 *
 * <ul>
 *   <li>known SKU: {@code {sku, found: true, onHand, asOf}}, where {@code asOf} is an ISO-8601 instant of the last
 *       stock change and is omitted if the SKU has never had a movement;</li>
 *   <li>unknown SKU: {@code {sku, found: false}}, with no other data and never a list of valid SKUs (spike 001);</li>
 *   <li>blank or missing SKU: {@code {found: false}}, with {@code sku} echoed only if it was present. A model that calls
 *       the tool without a SKU gets an answer instead of stalling the process on retries and an incident.</li>
 * </ul>
 *
 * <p>Fields without a value are omitted rather than set to {@code null}: an absent field is unambiguous for a model.
 * The map is built explicitly, so the contract does not depend on the JSON mapper's settings. The SKU is opaque and
 * passed through unchanged (no trimming, no case change).
 *
 * <p>Technical failures (database unreachable) propagate: the job fails and the engine retries, then raises an
 * incident. The class is {@code public} because the Camunda starter invokes the method reflectively.
 */
@Component
public class GetStockLevelWorker {

  static final String JOB_TYPE = "provisio.inventory.get-stock-level";
  static final String RESULT_VARIABLE = "stockLevel";

  private final InventoryQueries queries;

  public GetStockLevelWorker(InventoryQueries queries) {
    this.queries = queries;
  }

  @JobWorker(type = JOB_TYPE)
  public Map<String, Object> getStockLevel(@Variable(name = "sku") String sku) {
    return Map.of(RESULT_VARIABLE, stockLevel(sku));
  }

  Map<String, Object> stockLevel(String sku) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (sku != null) {
      result.put("sku", sku);
    }

    Optional<StockLevel> level = (sku == null || sku.isBlank()) ? Optional.empty() : queries.findStockLevel(sku);
    if (level.isEmpty()) {
      result.put("found", false);
      return result;
    }

    result.put("found", true);
    result.put("onHand", level.get().onHand());
    if (level.get().asOf() != null) {
      result.put("asOf", level.get().asOf().toString());
    }
    return result;
  }
}

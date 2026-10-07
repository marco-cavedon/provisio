package io.github.marcocavedon.provisio.spike;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dummy agent tool. The AI agent decides when to call it and fills {@code sku} via fromAi().
 *
 * <p>The worker returns a domain variable ({@code stockLevel}) and knows nothing about the agent:
 * the BPMN output mapping turns it into {@code toolCallResult}, which the ad-hoc sub-process
 * collects and feeds back to the LLM. The same worker can therefore serve non-agentic processes.
 */
@Component
public class StockLevelTool {

  private static final Logger log = LoggerFactory.getLogger(StockLevelTool.class);

  private record Stock(int onHand, int reorderPoint) {}

  private static final Map<String, Stock> STOCK = Map.of(
      "SKU-001", new Stock(42, 100),
      "SKU-002", new Stock(350, 120),
      "SKU-003", new Stock(0, 50));

  @JobWorker(type = "provisio.spike.get-stock-level")
  public Map<String, Object> getStockLevel(@Variable(name = "sku") String sku) {
    log.info("Agent tool call: getStockLevel(sku={})", sku);

    // LinkedHashMap instead of Map.of: values may be null and Map.of rejects nulls
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sku", sku);

    Stock stock = sku == null ? null : STOCK.get(sku.trim().toUpperCase());
    if (stock == null) {
      result.put("found", false);
      result.put("message", "Unknown SKU. Known SKUs: " + STOCK.keySet());
    } else {
      result.put("found", true);
      result.put("onHand", stock.onHand());
      result.put("reorderPoint", stock.reorderPoint());
    }
    return Map.of("stockLevel", result);
  }
}

package io.github.marcocavedon.provisio.inventory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The tool contract of {@code provisio.inventory.get-stock-level}, case by case. No Docker needed.
 */
class GetStockLevelWorkerTests {

  private final InventoryQueries queries = mock(InventoryQueries.class);
  private final GetStockLevelWorker worker = new GetStockLevelWorker(queries);

  @Test
  void knownSkuWithMovements() {
    when(queries.findStockLevel("SKU-1"))
        .thenReturn(Optional.of(new StockLevel("SKU-1", 7, Instant.parse("2026-10-27T10:15:30.123Z"))));

    assertThat(worker.getStockLevel("SKU-1")).isEqualTo(Map.of("stockLevel",
        Map.of("sku", "SKU-1", "found", true, "onHand", 7, "asOf", "2026-10-27T10:15:30.123Z")));
  }

  @Test
  void knownSkuWithoutMovementsOmitsAsOf() {
    when(queries.findStockLevel("SKU-NEW")).thenReturn(Optional.of(new StockLevel("SKU-NEW", 0, null)));

    assertThat(worker.stockLevel("SKU-NEW")).isEqualTo(Map.of("sku", "SKU-NEW", "found", true, "onHand", 0));
  }

  @Test
  void unknownSkuReturnsOnlyNotFound() {
    when(queries.findStockLevel("NO-SUCH-SKU")).thenReturn(Optional.empty());

    assertThat(worker.stockLevel("NO-SUCH-SKU")).isEqualTo(Map.of("sku", "NO-SUCH-SKU", "found", false));
  }

  @Test
  void blankSkuIsNotFoundWithoutQuerying() {
    assertThat(worker.stockLevel("  ")).isEqualTo(Map.of("sku", "  ", "found", false));
    verifyNoInteractions(queries);
  }

  @Test
  void missingSkuIsNotFoundWithoutQuerying() {
    assertThat(worker.stockLevel(null)).isEqualTo(Map.of("found", false));
    verifyNoInteractions(queries);
  }

  @Test
  void skuIsPassedThroughUnchanged() {
    when(queries.findStockLevel(" sku-1")).thenReturn(Optional.empty());

    assertThat(worker.stockLevel(" sku-1")).containsEntry("sku", " sku-1").containsEntry("found", false);
  }

  @Test
  void technicalFailuresPropagateSoTheJobFails() {
    when(queries.findStockLevel("SKU-1")).thenThrow(new DataAccessResourceFailureException("database unreachable"));

    assertThatThrownBy(() -> worker.getStockLevel("SKU-1"))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }
}

package io.github.marcocavedon.provisio.inventory.internal;

import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.Product;
import io.github.marcocavedon.provisio.inventory.ProductRegistration;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Products are inserted or updated, never deleted (ADR 0027). Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class RegisterProductsTests {

  private static final Product LUNCH_BAG = new Product("20725", "LUNCH BAG RED RETROSPOT", "BAGS", "EA");
  private static final Product JUMBO_BAG = new Product("85099B", "JUMBO BAG RED RETROSPOT", "BAGS", "EA");

  @Autowired
  private InventoryOperations operations;

  @Autowired
  private InventoryQueries queries;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    InventoryTestSupport.clear(mongoTemplate);
  }

  @Test
  void insertsNewProducts() {
    ProductRegistration result = operations.registerProducts(List.of(LUNCH_BAG, JUMBO_BAG));

    assertThat(result).isEqualTo(new ProductRegistration(2, 0, 0));
    assertThat(queries.findProduct("20725")).contains(LUNCH_BAG);
    // A registered product without movements has zero stock, not an unknown SKU
    assertThat(queries.findStockLevel("85099B")).contains(new StockLevel("85099B", 0, null));
  }

  @Test
  void updatesChangedProductsAndCountsUnchangedOnes() {
    operations.registerProducts(List.of(LUNCH_BAG, JUMBO_BAG));
    Product renamed = new Product("20725", "LUNCH BAG RED SPOTTY", "BAGS", "EA");

    ProductRegistration result = operations.registerProducts(List.of(renamed, JUMBO_BAG));

    assertThat(result).isEqualTo(new ProductRegistration(0, 1, 1));
    assertThat(queries.findProduct("20725")).contains(renamed);
  }

  @Test
  void productMissingFromALaterRegistrationStaysWithItsStock() {
    operations.registerProducts(List.of(LUNCH_BAG, JUMBO_BAG));
    operations.recordMovement(receipt("20725", 10, "receipt-1"));

    operations.registerProducts(List.of(JUMBO_BAG));

    assertThat(queries.findProduct("20725")).contains(LUNCH_BAG);
    assertThat(queries.findStockLevel("20725")).map(StockLevel::onHand).contains(10);
  }

  @Test
  void invalidInputIsRefusedAndNothingIsWritten() {
    assertThatThrownBy(() -> operations.registerProducts(
        List.of(JUMBO_BAG, new Product("20725", " ", "BAGS", "EA"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    assertThatThrownBy(() -> operations.registerProducts(List.of(JUMBO_BAG, JUMBO_BAG)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Repeated SKU");

    assertThat(queries.findProduct("85099B")).isEmpty();
  }
}

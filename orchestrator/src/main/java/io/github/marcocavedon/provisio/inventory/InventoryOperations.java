package io.github.marcocavedon.provisio.inventory;

import java.util.List;

/**
 * Write side of the inventory module. The only way to change stock: the snapshot is never written directly.
 */
public interface InventoryOperations {

  /**
   * Records a stock movement and updates the stock level in the same transaction.
   *
   * <p>Idempotent by {@link RecordMovement#idempotencyKey()}: recording the same movement again changes nothing and
   * returns the movement recorded the first time, flagged as already recorded. A rejected movement leaves no trace, so
   * a later attempt with the same key is evaluated again from scratch.
   *
   * @return the recorded movement, or a business outcome when the movement is rejected
   * @throws IdempotencyKeyConflictException if the key was already used for a different movement
   * @throws org.springframework.dao.DataAccessException on technical failures, including transient errors that
   *     persist after the bounded retries
   */
  MovementResult recordMovement(RecordMovement command);

  /**
   * Inserts new products and updates the name, category and unit of existing ones; never deletes (ADR 0027).
   *
   * <p>The whole input is validated before anything is written. Runs in a transaction, joining the caller's if there
   * is one: the seed registers products in the same transaction as the rest of its data.
   *
   * @throws IllegalArgumentException if a field is blank or a SKU is repeated
   */
  ProductRegistration registerProducts(List<Product> products);
}

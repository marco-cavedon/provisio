package io.github.marcocavedon.provisio.inventory;

import java.time.Instant;

/**
 * Command to record a stock movement.
 *
 * <p>An invalid command is a programming error of the caller, not a business outcome: the constructor throws
 * {@link IllegalArgumentException}.
 *
 * @param sku the SKU; must be a known product, otherwise the movement is rejected as {@code UNKNOWN_SKU}
 * @param type kind of movement
 * @param delta signed quantity, never zero: positive for {@code RECEIPT}, negative for {@code ISSUE}, either sign for
 *     {@code ADJUSTMENT}
 * @param occurredAt business time: when the movement happened. Stored with millisecond precision
 * @param idempotencyKey derived from the business event (for example purchase order and line), never random: a
 *     retried job must produce the same key
 * @param reference business cause of the movement; optional
 * @param reason free-text reason; required for {@code ADJUSTMENT}, optional otherwise
 */
public record RecordMovement(
    String sku,
    MovementType type,
    int delta,
    Instant occurredAt,
    String idempotencyKey,
    MovementReference reference,
    String reason) {

  public RecordMovement {
    requireText(sku, "sku");
    if (type == null) {
      throw new IllegalArgumentException("type is required");
    }
    if (delta == 0) {
      throw new IllegalArgumentException("delta must not be zero");
    }
    if (type == MovementType.RECEIPT && delta < 0) {
      throw new IllegalArgumentException("a RECEIPT must have a positive delta");
    }
    if (type == MovementType.ISSUE && delta > 0) {
      throw new IllegalArgumentException("an ISSUE must have a negative delta");
    }
    if (type == MovementType.ADJUSTMENT && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("an ADJUSTMENT requires a reason");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt is required");
    }
    requireText(idempotencyKey, "idempotencyKey");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}

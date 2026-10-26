package io.github.marcocavedon.provisio.inventory;

import java.time.Instant;

/**
 * A recorded stock movement. Movements are never updated or deleted.
 *
 * @param id identifier of the movement; opaque
 * @param sku the SKU
 * @param type kind of movement
 * @param delta signed quantity
 * @param occurredAt business time: when the movement happened
 * @param recordedAt system time: when it was written
 * @param idempotencyKey key supplied by the caller
 * @param reference business cause; may be {@code null}
 * @param reason free-text reason; may be {@code null} unless the type is {@code ADJUSTMENT}
 */
public record StockMovement(
    String id,
    String sku,
    MovementType type,
    int delta,
    Instant occurredAt,
    Instant recordedAt,
    String idempotencyKey,
    MovementReference reference,
    String reason) {
}

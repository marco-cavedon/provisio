package io.github.marcocavedon.provisio.inventory.internal;

import io.github.marcocavedon.provisio.inventory.MovementReference;
import io.github.marcocavedon.provisio.inventory.MovementType;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import io.github.marcocavedon.provisio.inventory.StockMovement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code stock_movements}: append-only ledger (ADR 0022). Documents are inserted, never updated or deleted.
 */
@Document(collection = StockMovementDocument.COLLECTION)
public record StockMovementDocument(
    @Id ObjectId id,
    String sku,
    MovementType type,
    int delta,
    Instant occurredAt,
    Instant recordedAt,
    String idempotencyKey,
    MovementReference reference,
    String reason) {

  public static final String COLLECTION = "stock_movements";

  /**
   * Builds the document for a new movement. Times are truncated to milliseconds, MongoDB's date precision, so that
   * what is returned to the caller equals what is stored.
   */
  static StockMovementDocument from(RecordMovement command, ObjectId id, Instant recordedAt) {
    return new StockMovementDocument(
        id,
        command.sku(),
        command.type(),
        command.delta(),
        command.occurredAt().truncatedTo(ChronoUnit.MILLIS),
        recordedAt.truncatedTo(ChronoUnit.MILLIS),
        command.idempotencyKey(),
        command.reference(),
        command.reason());
  }

  /**
   * Whether this recorded movement is the one the command describes. Used when an idempotency key is reused: the
   * same key must mean the same business event.
   */
  boolean describes(RecordMovement command) {
    return sku.equals(command.sku())
        && type == command.type()
        && delta == command.delta()
        && occurredAt.equals(command.occurredAt().truncatedTo(ChronoUnit.MILLIS));
  }

  StockMovement toStockMovement() {
    return new StockMovement(
        id.toHexString(), sku, type, delta, occurredAt, recordedAt, idempotencyKey, reference, reason);
  }
}

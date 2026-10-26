package io.github.marcocavedon.provisio.inventory;

import java.util.Objects;

/**
 * Outcome of {@link InventoryOperations#recordMovement(RecordMovement)}.
 *
 * <p>Sealed, so a {@code switch} over it must handle both cases: a rejection cannot be ignored by accident.
 */
public sealed interface MovementResult {

  /**
   * The movement is recorded and the stock level updated.
   *
   * @param movement the recorded movement; when {@code alreadyRecorded}, the one recorded the first time
   * @param alreadyRecorded {@code true} if the idempotency key had already been used for this movement, so nothing
   *     changed in this call
   */
  record Recorded(StockMovement movement, boolean alreadyRecorded) implements MovementResult {

    public Recorded {
      Objects.requireNonNull(movement, "movement");
    }
  }

  /**
   * The movement is rejected and nothing was written.
   *
   * @param reason why the movement was rejected
   */
  record Rejected(Reason reason) implements MovementResult {

    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Business reasons for rejecting a movement. */
  enum Reason {

    /** The movement would make on-hand stock negative. */
    INSUFFICIENT_STOCK,

    /** The SKU is not a known product. */
    UNKNOWN_SKU
  }
}

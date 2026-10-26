package io.github.marcocavedon.provisio.inventory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * An invalid command is a caller's bug and fails at construction, before any database access. No Docker needed.
 */
class RecordMovementTests {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T10:00:00Z");

  @Test
  void acceptsValidMovementsOfEveryType() {
    assertThatCode(() -> {
      new RecordMovement("SKU-1", MovementType.RECEIPT, 5, OCCURRED_AT, "k1",
          new MovementReference("purchase-order", "PO-1"), null);
      new RecordMovement("SKU-1", MovementType.ISSUE, -2, OCCURRED_AT, "k2", null, null);
      new RecordMovement("SKU-1", MovementType.ADJUSTMENT, -1, OCCURRED_AT, "k3", null, "cycle count");
      new RecordMovement("SKU-1", MovementType.ADJUSTMENT, 1, OCCURRED_AT, "k4", null, "cycle count");
    }).doesNotThrowAnyException();
  }

  @Test
  void rejectsZeroDelta() {
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.ADJUSTMENT, 0, OCCURRED_AT, "k", null, "r"),
        "delta");
  }

  @Test
  void rejectsReceiptWithNegativeDelta() {
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.RECEIPT, -1, OCCURRED_AT, "k", null, null),
        "RECEIPT");
  }

  @Test
  void rejectsIssueWithPositiveDelta() {
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.ISSUE, 1, OCCURRED_AT, "k", null, null),
        "ISSUE");
  }

  @Test
  void rejectsAdjustmentWithoutReason() {
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.ADJUSTMENT, 1, OCCURRED_AT, "k", null, " "),
        "reason");
  }

  @Test
  void rejectsMissingFields() {
    assertInvalid(() -> new RecordMovement(" ", MovementType.RECEIPT, 1, OCCURRED_AT, "k", null, null), "sku");
    assertInvalid(() -> new RecordMovement("SKU-1", null, 1, OCCURRED_AT, "k", null, null), "type");
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.RECEIPT, 1, null, "k", null, null), "occurredAt");
    assertInvalid(() -> new RecordMovement("SKU-1", MovementType.RECEIPT, 1, OCCURRED_AT, "", null, null),
        "idempotencyKey");
  }

  @Test
  void rejectsIncompleteReference() {
    assertInvalid(() -> new MovementReference("", "PO-1"), "kind");
    assertInvalid(() -> new MovementReference("purchase-order", null), "id");
  }

  private static void assertInvalid(Runnable construction, String messagePart) {
    assertThatThrownBy(construction::run)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(messagePart);
  }
}

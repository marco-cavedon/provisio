package io.github.marcocavedon.provisio.inventory;

/**
 * Kind of stock movement. The type fixes the sign of the quantity.
 */
public enum MovementType {

  /** Goods received; positive quantity. */
  RECEIPT,

  /** Goods issued, e.g. sold; negative quantity. */
  ISSUE,

  /** Correction, e.g. after a cycle count; either sign, reason required. */
  ADJUSTMENT
}

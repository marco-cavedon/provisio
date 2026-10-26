package io.github.marcocavedon.provisio.inventory;

/**
 * The idempotency key was already used for a different movement.
 *
 * <p>This is a bug in how the caller derives its keys (random, or too coarse), not a business outcome: returning the
 * existing movement would hide it, so the operation fails. In a job worker the job fails and, after the engine's
 * retries, raises an incident.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

  private final String idempotencyKey;

  public IdempotencyKeyConflictException(String idempotencyKey, String existingMovementId) {
    super("Idempotency key '" + idempotencyKey + "' was already used for a different movement ("
        + existingMovementId + ")");
    this.idempotencyKey = idempotencyKey;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}

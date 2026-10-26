package io.github.marcocavedon.provisio.inventory.internal;

import com.mongodb.MongoException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a whole transaction a bounded number of times when MongoDB reports that retrying is safe.
 *
 * <p>Retryable failures:
 * <ul>
 *   <li>errors labelled {@code TransientTransactionError}, e.g. a write conflict with a concurrent transaction on the
 *       same stock level;</li>
 *   <li>errors labelled {@code UnknownTransactionCommitResult}: the commit may or may not have happened. Retrying the
 *       whole transaction is safe only because movements are idempotent: if the first commit went through, the retry
 *       hits the unique idempotency key and returns the movement already recorded;</li>
 *   <li>{@link ConcurrentSnapshotCreationException}: another transaction created the same stock level first.</li>
 * </ul>
 *
 * <p>The driver's {@code ClientSession.withTransaction} retries too, but it bypasses Spring's transaction management
 * and keeps retrying for up to two minutes, longer than a job's timeout. An explicit loop is bounded and testable.
 */
final class TransactionRetry {

  static final int MAX_ATTEMPTS = 3;

  private static final int MAX_CAUSE_DEPTH = 10;
  private static final long BASE_BACKOFF_MILLIS = 10;

  private TransactionRetry() {
  }

  static <T> T execute(Supplier<T> transaction) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transaction.get();
      } catch (RuntimeException failure) {
        if (attempt >= MAX_ATTEMPTS || !isRetryable(failure)) {
          throw failure;
        }
        backOff(attempt);
      }
    }
  }

  /**
   * Whether the failure, or any of its causes, is safe to retry. Spring translates driver exceptions, so the
   * MongoDB error labels are looked up along the cause chain.
   */
  static boolean isRetryable(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
      if (current instanceof ConcurrentSnapshotCreationException) {
        return true;
      }
      if (current instanceof MongoException mongoException
          && (mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
              || mongoException.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /** Short randomised backoff, so that conflicting transactions do not retry in lockstep. */
  private static void backOff(int attempt) {
    long millis = BASE_BACKOFF_MILLIS * attempt + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS * attempt);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying a transaction", interrupted);
    }
  }

  /** Another transaction created the stock level of the same SKU first; the whole transaction can be retried. */
  static final class ConcurrentSnapshotCreationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ConcurrentSnapshotCreationException(String sku, Throwable cause) {
      super("Stock level of SKU '" + sku + "' was created by a concurrent transaction", cause);
    }
  }
}

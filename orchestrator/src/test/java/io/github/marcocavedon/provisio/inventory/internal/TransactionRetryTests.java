package io.github.marcocavedon.provisio.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoException;
import io.github.marcocavedon.provisio.inventory.internal.TransactionRetry.ConcurrentSnapshotCreationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Which failures are retried, and how many times. No Docker needed: failures are built by hand.
 */
class TransactionRetryTests {

  @Test
  void retriesATransientErrorFoundAlongTheCauseChain() {
    AtomicInteger attempts = new AtomicInteger();

    String result = TransactionRetry.execute(() -> {
      if (attempts.incrementAndGet() == 1) {
        // Spring wraps driver exceptions: the label is on the cause
        throw new RuntimeException("translated", labelled(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL));
      }
      return "committed";
    });

    assertThat(result).isEqualTo("committed");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void givesUpAfterTheMaximumNumberOfAttempts() {
    AtomicInteger attempts = new AtomicInteger();
    RuntimeException conflict = labelled(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

    assertThatThrownBy(() -> TransactionRetry.execute(() -> {
      attempts.incrementAndGet();
      throw conflict;
    })).isSameAs(conflict);

    assertThat(attempts).hasValue(TransactionRetry.MAX_ATTEMPTS);
  }

  @Test
  void doesNotRetryOtherFailures() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> TransactionRetry.execute(() -> {
      attempts.incrementAndGet();
      throw new IllegalStateException("not transient");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(attempts).hasValue(1);
  }

  @Test
  void classifiesFailures() {
    assertThat(TransactionRetry.isRetryable(labelled(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))).isTrue();
    assertThat(TransactionRetry.isRetryable(new ConcurrentSnapshotCreationException("SKU-1", null))).isTrue();
    assertThat(TransactionRetry.isRetryable(new MongoException("no label"))).isFalse();
    assertThat(TransactionRetry.isRetryable(new RuntimeException("plain"))).isFalse();
  }

  private static MongoException labelled(String label) {
    MongoException exception = new MongoException("simulated");
    exception.addLabel(label);
    return exception;
  }
}

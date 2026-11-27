package com.fairtix.queue.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The admission lock must end before a single instance's next run is due. */
class QueueAdmissionLockSettingsTest {

  @Test
  void defaultsPass() {
    assertThatCode(() -> scheduler(30_000, Duration.ofSeconds(28)).checkLockFitsInterval())
        .doesNotThrowAnyException();
  }

  @Test
  void lockAsLongAsTheIntervalFailsStartup() {
    assertThatThrownBy(() -> scheduler(10_000, Duration.ofSeconds(28)).checkLockFitsInterval())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("queue.admission.lock-at-least-for");
    assertThatThrownBy(() -> scheduler(28_000, Duration.ofSeconds(28)).checkLockFitsInterval())
        .isInstanceOf(IllegalStateException.class);
  }

  private static QueueAdmissionScheduler scheduler(long intervalMs, Duration lockAtLeastFor) {
    QueueAdmissionScheduler scheduler = new QueueAdmissionScheduler(null, null, null, null, null, null);
    ReflectionTestUtils.setField(scheduler, "intervalMs", intervalMs);
    ReflectionTestUtils.setField(scheduler, "lockAtLeastFor", lockAtLeastFor);
    return scheduler;
  }
}

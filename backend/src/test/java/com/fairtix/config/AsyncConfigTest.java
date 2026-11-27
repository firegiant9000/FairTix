package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The mail pools' shape: fixed threads, never silently drop mail, and the test-suite switch. */
class AsyncConfigTest {

  private final AsyncConfig config = new AsyncConfig();

  /**
   * A ThreadPoolExecutor grows past its core size only once the queue is full, so a pool with
   * core below max would run on its core threads alone until then (review finding).
   */
  @Test
  void mailExecutor_isAFixedPoolOfFour() {
    ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) config.mailExecutor(new MdcTaskDecorator(), true);
    try {
      assertThat(pool.getThreadNamePrefix()).isEqualTo("fairtix-mail-");
      assertThat(pool.getCorePoolSize()).isEqualTo(4);
      assertThat(pool.getMaxPoolSize()).isEqualTo(4);
      assertThat(pool.getQueueCapacity()).isEqualTo(1000);
      assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
          .isInstanceOf(AsyncConfig.RunInlineUnlessShutDown.class);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void bulkMailExecutor_isASeparatePool() {
    ThreadPoolTaskExecutor pool =
        (ThreadPoolTaskExecutor) config.bulkMailExecutor(new MdcTaskDecorator(), true);
    try {
      assertThat(pool.getThreadNamePrefix()).isEqualTo("fairtix-bulkmail-");
      assertThat(pool.getCorePoolSize()).isEqualTo(pool.getMaxPoolSize());
      assertThat(pool.getQueueCapacity()).isEqualTo(20_000);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void mailExecutors_whenAsyncIsOff_runOnTheCaller() {
    assertThat(config.mailExecutor(new MdcTaskDecorator(), false)).isInstanceOf(SyncTaskExecutor.class);
    assertThat(config.bulkMailExecutor(new MdcTaskDecorator(), false)).isInstanceOf(SyncTaskExecutor.class);
  }

  @Test
  void rejectedWhileRunning_runsOnTheCaller() {
    ThreadPoolExecutor live = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    try {
      AtomicInteger runs = new AtomicInteger();
      new AsyncConfig.RunInlineUnlessShutDown().rejectedExecution(runs::incrementAndGet, live);
      assertThat(runs).hasValue(1);
    } finally {
      live.shutdown();
    }
  }

  /** CallerRunsPolicy would drop this silently; the handler logs it. */
  @Test
  void rejectedAfterShutdown_isLoggedNotRun() {
    ThreadPoolExecutor stopped = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    stopped.shutdown();
    Logger logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      AtomicInteger runs = new AtomicInteger();
      new AsyncConfig.RunInlineUnlessShutDown().rejectedExecution(runs::incrementAndGet, stopped);

      assertThat(runs).hasValue(0);
      assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("Notification email dropped"));
    } finally {
      logger.detachAppender(logs);
    }
  }
}

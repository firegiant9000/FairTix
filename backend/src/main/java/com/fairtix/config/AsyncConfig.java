package com.fairtix.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableAsync
public class AsyncConfig {

  private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

  @Bean
  public TaskDecorator mdcTaskDecorator() {
    return new MdcTaskDecorator();
  }

  @Bean(name = "taskExecutor")
  public Executor taskExecutor(TaskDecorator mdcTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("fairtix-async-");
    executor.setTaskDecorator(mdcTaskDecorator);
    executor.initialize();
    return executor;
  }

  /**
   * Runs the SMTP sends of {@code NotificationGate} for one-recipient mail (verification,
   * password reset, orders, transfers, refunds), so a slow mail server doesn't hold a request
   * thread and its pooled database connection. Its own pool, so mail can't starve other async
   * work. With {@code fairtix.mail.async=false} (the test suites) sends run on the caller's
   * thread.
   */
  @Bean(name = "mailExecutor")
  public TaskExecutor mailExecutor(TaskDecorator mdcTaskDecorator,
      @Value("${fairtix.mail.async:true}") boolean async) {
    return async ? mailPool("fairtix-mail-", 4, 1000, mdcTaskDecorator) : new SyncTaskExecutor();
  }

  /**
   * Bulk mail (one per ticket when an event is cancelled), kept off {@link #mailExecutor} so
   * thousands of cancellation mails can't queue ahead of a password reset.
   */
  @Bean(name = "bulkMailExecutor")
  public TaskExecutor bulkMailExecutor(TaskDecorator mdcTaskDecorator,
      @Value("${fairtix.mail.async:true}") boolean async) {
    return async ? mailPool("fairtix-bulkmail-", 2, 20_000, mdcTaskDecorator) : new SyncTaskExecutor();
  }

  /**
   * A fixed pool: a ThreadPoolExecutor only grows past its core size once the queue is full,
   * so core = max. A full queue runs the send on the caller's thread (slower, but nothing is
   * dropped); shutdown waits up to 30 s for queued mail.
   */
  private static ThreadPoolTaskExecutor mailPool(String prefix, int threads, int queue,
      TaskDecorator decorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setAllowCoreThreadTimeOut(true);
    executor.setQueueCapacity(queue);
    executor.setThreadNamePrefix(prefix);
    executor.setTaskDecorator(decorator);
    executor.setRejectedExecutionHandler(new RunInlineUnlessShutDown());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }

  /**
   * Like {@link ThreadPoolExecutor.CallerRunsPolicy}, which silently discards a task once the
   * pool is shut down; this logs it instead, so mail lost in a shutdown leaves a trace.
   */
  static final class RunInlineUnlessShutDown implements RejectedExecutionHandler {
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
      if (executor.isShutdown()) {
        log.warn("Notification email dropped: the mail pool is shut down");
        return;
      }
      task.run();
    }
  }
}

package com.fairtix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

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
}

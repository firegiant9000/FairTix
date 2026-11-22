package com.fairtix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-in-view on every request except the waiting-room stream.
 *
 * <p>Replaces Spring Boot's registration, which backs off when this bean exists,
 * so the rest of the app keeps the behaviour it was written against. The stream is
 * an async request that stays open for up to three minutes; under open-in-view its
 * EntityManager lives that long and keeps the pooled connection its initial status
 * read acquired, so ten open waiting rooms took the whole pool. Without it, that
 * read is an ordinary {@code @Transactional} call and returns the connection on
 * commit. See {@code QueueStreamPoolIT}.
 *
 * <p>Two things keep Boot's behaviour. It runs last, as Boot's registration did:
 * ahead of {@code OrgScopeInterceptor}, a denial would hold the request's connection
 * while its {@code REQUIRES_NEW} audit write waits for a second one. And
 * {@code spring.jpa.open-in-view=false} still turns it off everywhere.
 */
@Configuration
@ConditionalOnBooleanProperty(name = "spring.jpa.open-in-view", matchIfMissing = true)
public class OpenInViewConfig implements WebMvcConfigurer {

  static final String QUEUE_STREAM_PATH = "/api/events/*/queue/stream";

  @Bean
  public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
    return new OpenEntityManagerInViewInterceptor();
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor())
        .excludePathPatterns(QUEUE_STREAM_PATH)
        .order(Ordered.LOWEST_PRECEDENCE);
  }
}

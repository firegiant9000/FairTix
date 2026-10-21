package com.fairtix.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // Bounded so a hung Redis fails a request in about 2.5 s (two 1 s attempts, 0.5 s
    // apart) instead of Redisson's default of about 16 s. RateLimitFilter then fails
    // closed (auth) or open (everything else).
    @Value("${fairtix.redis.timeout-ms:1000}")
    private int timeoutMs;

    @Value("${fairtix.redis.retry-attempts:1}")
    private int retryAttempts;

    @Value("${fairtix.redis.retry-interval-ms:500}")
    private int retryIntervalMs;

    @Value("${fairtix.redis.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Bean
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient() {
        Config config = new Config();
        var server = config.useSingleServer()
              .setAddress("redis://" + redisHost + ":" + redisPort)
              .setTimeout(timeoutMs)
              .setRetryAttempts(retryAttempts)
              .setRetryInterval(retryIntervalMs)
              .setConnectTimeout(connectTimeoutMs);

        if (redisPassword != null && !redisPassword.isBlank()) {
            server.setPassword(redisPassword);
        }

        return Redisson.create(config);
    }
}
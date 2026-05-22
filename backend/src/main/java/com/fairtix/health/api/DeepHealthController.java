package com.fairtix.health.api;

import com.stripe.Stripe;
import com.stripe.model.Balance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /_health/deep returns per-dependency status (db, redis, mail, stripe) for use by
 * the staging status dashboard and oncall. Distinct from /actuator/health, which
 * collapses everything into one UP/DOWN for load-balancer probes.
 */
@RestController
@RequestMapping("/_health")
public class DeepHealthController {

  private final JdbcTemplate jdbc;
  private final RedisConnectionFactory redis;
  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final boolean stripeEnabled;

  public DeepHealthController(
      JdbcTemplate jdbc,
      RedisConnectionFactory redis,
      ObjectProvider<JavaMailSender> mailSenderProvider,
      @Value("${stripe.enabled:false}") boolean stripeEnabled) {
    this.jdbc = jdbc;
    this.redis = redis;
    this.mailSenderProvider = mailSenderProvider;
    this.stripeEnabled = stripeEnabled;
  }

  @GetMapping("/deep")
  public ResponseEntity<Map<String, Object>> deep() {
    Map<String, Object> components = new LinkedHashMap<>();
    boolean allUp = true;

    allUp &= record(components, "db", this::checkDb);
    allUp &= record(components, "redis", this::checkRedis);
    allUp &= record(components, "mail", this::checkMail);
    allUp &= record(components, "stripe", this::checkStripe);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", allUp ? "UP" : "DEGRADED");
    body.put("components", components);
    return ResponseEntity.status(allUp ? 200 : 503).body(body);
  }

  private boolean record(Map<String, Object> out, String name, Check check) {
    long start = System.nanoTime();
    Map<String, Object> entry = new LinkedHashMap<>();
    try {
      String detail = check.run();
      entry.put("status", "UP");
      if (detail != null) entry.put("detail", detail);
      out.put(name, entry);
      entry.put("latencyMs", elapsedMs(start));
      return true;
    } catch (Exception e) {
      entry.put("status", "DOWN");
      entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      entry.put("latencyMs", elapsedMs(start));
      out.put(name, entry);
      return false;
    }
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private String checkDb() {
    Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
    if (one == null || one != 1) throw new IllegalStateException("unexpected response");
    return null;
  }

  private String checkRedis() throws Exception {
    try (var conn = redis.getConnection()) {
      String pong = conn.ping();
      if (!"PONG".equalsIgnoreCase(pong)) throw new IllegalStateException("ping=" + pong);
    }
    return null;
  }

  private String checkMail() throws Exception {
    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) return "not configured";
    if (sender instanceof JavaMailSenderImpl impl) {
      impl.testConnection();
      return impl.getHost() + ":" + impl.getPort();
    }
    return "configured";
  }

  private String checkStripe() throws Exception {
    if (!stripeEnabled) return "disabled";
    Balance balance = Balance.retrieve();
    return "livemode=" + balance.getLivemode();
  }

  @FunctionalInterface
  private interface Check {
    String run() throws Exception;
  }
}

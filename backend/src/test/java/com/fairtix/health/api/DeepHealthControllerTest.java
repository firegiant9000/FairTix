package com.fairtix.health.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@code /_health/deep}'s body. */
class DeepHealthControllerTest {

  private JdbcTemplate jdbc;
  private RedisConnectionFactory redis;
  private JavaMailSenderImpl sender;
  private ObjectProvider<JavaMailSender> provider;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    redis = mock(RedisConnectionFactory.class);
    RedisConnection conn = mock(RedisConnection.class);
    when(redis.getConnection()).thenReturn(conn);
    when(conn.ping()).thenReturn("PONG");
    sender = mock(JavaMailSenderImpl.class);
    provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(sender);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> component(Map<String, Object> body, String name) {
    return (Map<String, Object>) ((Map<String, Object>) body.get("components")).get(name);
  }

  /**
   * With mail switched off, report {@code disabled} instead of probing the SMTP host
   * (main always sets {@code spring.mail.host}, so the sender bean exists).
   * Red on commit {@code 1899c78}.
   */
  @Test
  void mailDisabled_reportsDisabled_noProbe() throws Exception {
    var controller = new DeepHealthController(jdbc, redis, provider, false, false);
    Map<String, Object> body = controller.deep().getBody();

    assertThat(component(body, "mail")).containsEntry("status", "UP").containsEntry("detail", "disabled");
    verify(sender, never()).testConnection();
    assertThat(body).containsEntry("status", "UP");
  }

  /**
   * A failing dependency reports its exception type only: connection errors quote
   * hosts, ports and sometimes users. Red on commit {@code f1bfff8}.
   */
  @Test
  void deepHealth_errorHasNoMessage() {
    when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new DataAccessResourceFailureException("connect to db-secret.internal:5432 as fairtix"));
    var controller = new DeepHealthController(jdbc, redis, provider, false, false);

    Map<String, Object> db = component(controller.deep().getBody(), "db");

    assertThat(db).containsEntry("status", "DOWN")
        .containsEntry("error", "DataAccessResourceFailureException");
  }
}

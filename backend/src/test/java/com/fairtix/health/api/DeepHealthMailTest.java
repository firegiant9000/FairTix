package com.fairtix.health.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

/**
 * With mail switched off, {@code /_health/deep} reports {@code disabled} instead of
 * probing the SMTP host (main always sets {@code spring.mail.host}, so the sender bean
 * exists). Red on commit {@code <red>}.
 */
class DeepHealthMailTest {

  @Test
  @SuppressWarnings("unchecked")
  void mailDisabled_reportsDisabled_noProbe() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    RedisConnection conn = mock(RedisConnection.class);
    when(redis.getConnection()).thenReturn(conn);
    when(conn.ping()).thenReturn("PONG");
    JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(sender);

    var controller = new DeepHealthController(jdbc, redis, provider, false, false);
    Map<String, Object> body = controller.deep().getBody();

    Map<String, Object> mail = (Map<String, Object>) ((Map<String, Object>) body.get("components")).get("mail");
    assertThat(mail).containsEntry("status", "UP").containsEntry("detail", "disabled");
    verify(sender, never()).testConnection();
    assertThat(body).containsEntry("status", "UP");
  }
}

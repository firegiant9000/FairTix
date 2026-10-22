package com.fairtix.config;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.support.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator exposure as deployed (RM:324: "Expose only {@code health}").
 *
 * <p>The test classpath's {@code application.properties} shadows the main file, so this
 * context copies every {@code management.*} key from {@code src/main/resources/
 * application.properties} on disk. A second check scans every profile file. Runs on the
 * container suite, so the health status reflects real PostgreSQL and Redis.
 *
 * <p>Red on commit {@code f1bfff8}: main and staging exposed {@code info} and
 * {@code SecurityConfig} permitted it anonymously; staging showed health details to
 * any signed-in user.
 */
class ActuatorExposureIT extends PostgresIT {

  private static final Path RESOURCES = Path.of("src/main/resources");

  @DynamicPropertySource
  static void mainFileManagement(DynamicPropertyRegistry registry) {
    Properties main = load(RESOURCES.resolve("application.properties"));
    main.stringPropertyNames().stream()
        .filter(k -> k.startsWith("management."))
        .forEach(k -> registry.add(k, () -> main.getProperty(k)));
  }

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void health_anonymous_200_statusOnly() throws Exception {
    // Boot also lists the probe group names ("groups"), which carry no dependency data.
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void info_anonymous_not200() throws Exception {
    assertThat(mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus())
        .isNotEqualTo(200);
  }

  @Test
  void env_beans_mappings_not200() throws Exception {
    for (String path : new String[] {"/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/mappings",
        "/actuator/configprops", "/actuator/heapdump", "/actuator"}) {
      assertThat(mockMvc.perform(get(path)
              .with(WithMockPrincipal.admin(UUID.randomUUID(), "admin@test.com")))
          .andReturn().getResponse().getStatus())
          .as(path).isNotEqualTo(200);
    }
  }

  @Test
  void deepHealth_nonAdmin_403() throws Exception {
    mockMvc.perform(get("/_health/deep")
            .with(WithMockPrincipal.user(UUID.randomUUID(), "user@test.com")))
        .andExpect(status().isForbidden());
  }

  @Test
  void noProfileExposesMoreThanHealth() throws IOException {
    for (Path file : profileFiles()) {
      String include = load(file).getProperty("management.endpoints.web.exposure.include");
      if (file.getFileName().toString().equals("application.properties")) {
        assertThat(include).as(file.toString()).isEqualTo("health");
      } else if (include != null) {
        assertThat(include.trim()).as(file.toString()).isEqualTo("health");
      }
    }
  }

  @Test
  void noProfileShowsHealthDetails() throws IOException {
    for (Path file : profileFiles()) {
      Properties props = load(file);
      for (String key : new String[] {"management.endpoint.health.show-details",
          "management.endpoint.health.show-components"}) {
        String value = props.getProperty(key);
        if (file.getFileName().toString().equals("application.properties")
            && key.endsWith("show-details")) {
          assertThat(value).as(file + " " + key).isEqualTo("never");
        } else if (value != null) {
          assertThat(value.trim()).as(file + " " + key).isEqualTo("never");
        }
      }
    }
  }

  private static Iterable<Path> profileFiles() throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(RESOURCES, "application*.properties")) {
      return java.util.stream.StreamSupport.stream(files.spliterator(), false).toList();
    }
  }

  private static Properties load(Path file) {
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file)) {
      props.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return props;
  }
}

package com.fairtix.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.webjars.WebJarVersionLocator;

/**
 * springdoc 3 serves swagger-ui from the webjar folder named by springdoc.swagger-ui.version.
 * The test application.properties replaces main's on the test classpath, so
 * SecurityHeadersTest never sees the production value; this reads main's file directly.
 */
class SwaggerUiVersionPinTest {

  @Test
  void mainPropertiesNameTheSwaggerUiWebjarOnTheClasspath() throws Exception {
    Properties main = new Properties();
    try (Reader in = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
      main.load(in);
    }
    String webjarVersion = new WebJarVersionLocator().version("swagger-ui");
    assertNotNull(webjarVersion, "swagger-ui webjar not on the classpath");
    assertEquals(webjarVersion, main.getProperty("springdoc.swagger-ui.version"));
  }
}

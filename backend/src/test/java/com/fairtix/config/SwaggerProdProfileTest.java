package com.fairtix.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Swagger UI and the OpenAPI spec are not published under the prod profile. The default
 * profile still serves them (SecurityHeadersTest asserts 200 there).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("prod")
class SwaggerProdProfileTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void apiDocsAndSwaggerUiAreNotServed() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
  }
}

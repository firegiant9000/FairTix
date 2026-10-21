package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client mistakes reach the client as 4xx with the usual JSON body, and are
 * logged at warn, never error (Review Focus 5).
 *
 * <p>Red on commit {@code RED_COMMIT}: none of these exceptions had a handler, so
 * the {@code Exception} catch-all returned 500 {@code INTERNAL_ERROR} and logged
 * each one at error.
 */
class GlobalExceptionHandlerClientErrorTest {

  private MockMvc mockMvc;
  private ListAppender<ILoggingEvent> logs;
  private Logger handlerLogger;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
        .standaloneSetup(new ClientErrorController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    logs = new ListAppender<>();
    logs.start();
    handlerLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(logs);
    assertThat(logs.list)
        .as("client errors must not be logged at error")
        .noneMatch(e -> e.getLevel() == Level.ERROR);
    assertThat(logs.list)
        .as("client errors are logged at warn")
        .anyMatch(e -> e.getLevel() == Level.WARN);
  }

  @Test
  void malformedJson_is400MalformedRequest() throws Exception {
    mockMvc.perform(post("/test/body")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"name\": \"secret-value"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        .andExpect(jsonPath("$.message").value(not(containsString("secret-value"))));
  }

  @Test
  void missingBody_is400MalformedRequest() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void invalidListElement_is400ValidationError() throws Exception {
    mockMvc.perform(post("/test/list")
            .contentType(MediaType.APPLICATION_JSON)
            .content("[{ \"name\": null }]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void constrainedRequestParam_is400ValidationError() throws Exception {
    mockMvc.perform(get("/test/param").param("n", "100"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void constraintViolationException_is400ValidationError() throws Exception {
    mockMvc.perform(get("/test/violation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void wrongMethod_is405WithAllow() throws Exception {
    mockMvc.perform(get("/test/body"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(header().string("Allow", containsString("POST")));
  }

  @Test
  void wrongContentType_is415() throws Exception {
    mockMvc.perform(post("/test/body")
            .contentType(MediaType.TEXT_PLAIN)
            .content("name=x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void missingRequestParam_is400() throws Exception {
    mockMvc.perform(get("/test/param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
  }

  @Test
  void unparseablePathVariable_is400() throws Exception {
    mockMvc.perform(get("/test/item/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        .andExpect(jsonPath("$.message").value(not(containsString("not-a-uuid"))));
  }

  record Item(@NotNull String name) {}

  @RestController
  static class ClientErrorController {
    @PostMapping("/test/body")
    String body(@Valid @RequestBody Item item) {
      return item.name();
    }

    @PostMapping("/test/list")
    int list(@Valid @RequestBody List<Item> items) {
      return items.size();
    }

    @GetMapping("/test/param")
    int param(@RequestParam("n") @Max(10) int n) {
      return n;
    }

    @GetMapping("/test/violation")
    void violation() {
      var violations = Validation.buildDefaultValidatorFactory().getValidator()
          .validate(new Item(null));
      throw new ConstraintViolationException(violations);
    }

    @GetMapping("/test/item/{id}")
    UUID item(@PathVariable("id") UUID id) {
      return id;
    }
  }
}

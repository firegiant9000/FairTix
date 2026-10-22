package com.fairtix.config;

import com.fairtix.auth.application.JwtAuthenticationFilter;
import com.fairtix.fraud.api.StepUpFilter;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

@EnableMethodSecurity(jsr250Enabled = true)
@Configuration
public class SecurityConfig {

  private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
  private static final String PRINT_VIEW_CSP =
      "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'";

  private static final RequestMatcher API_PATHS = new OrRequestMatcher(
      PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
      PathPatternRequestMatcher.withDefaults().matcher("/auth/**"));

  /** HTML opened in a browser tab for print-to-PDF (ReportsController, WillCallController). */
  private static final RequestMatcher PRINT_VIEWS = new OrRequestMatcher(
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.GET, "/api/organizations/*/reports/events/*/settlement.html"),
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.GET, "/api/organizations/*/reports/events/*/dos.html"),
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/organizer/will-call/print"));

  private final JwtAuthenticationFilter jwtFilter;
  private final StepUpFilter stepUpFilter;
  private final AllowedOrigins allowedOrigins;

  public SecurityConfig(
      JwtAuthenticationFilter jwtFilter,
      StepUpFilter stepUpFilter,
      AllowedOrigins allowedOrigins) {
    this.jwtFilter = jwtFilter;
    this.stepUpFilter = stepUpFilter;
    this.allowedOrigins = allowedOrigins;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuthenticationManager authenticationManager(
      AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

    http
        .cors(cors -> cors.configurationSource(request -> {
          CorsConfiguration config = new CorsConfiguration();
          config.setAllowedOrigins(allowedOrigins.list());
          config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
          // What the SPA sends (client.js, holdsApi.js) plus Bearer for API clients;
          // "*" with credentials echoed back any header a page asked for.
          config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Organization-Id"));
          config.setAllowCredentials(true);
          return config;
        }))
        // Token CSRF can't work cross-site (the SPA can't read the API's cookies);
        // OriginCheckFilter, below, checks Origin/Referer on cookie requests instead.
        .csrf(csrf -> csrf.disable())
        .headers(headers -> headers
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            // Only sent on secure requests; behind the ALB, ForwardedHeadersConfig's
            // RemoteIpValve makes those secure from X-Forwarded-Proto.
            .httpStrictTransportSecurity(hsts -> hsts
                .maxAgeInSeconds(31_536_000)
                .includeSubDomains(true))
            // JSON only, so nothing may load or frame it; swagger-ui needs scripts and
            // is left out. The print views are HTML styled by an inline <style>.
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                new AndRequestMatcher(API_PATHS, new NegatedRequestMatcher(PRINT_VIEWS)),
                new StaticHeadersWriter("Content-Security-Policy", API_CSP)))
            .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                PRINT_VIEWS,
                new StaticHeadersWriter("Content-Security-Policy", PRINT_VIEW_CSP))))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/_health/deep").hasRole("ADMIN")
            .requestMatchers("/auth/**").permitAll()
            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/venues/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/sitemap.xml", "/robots.txt", "/embed.js").permitAll()
            .requestMatchers("/api/webhooks/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
            }))
        .addFilterAfter(new OriginCheckFilter(allowedOrigins), CorsFilter.class)
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(stepUpFilter, JwtAuthenticationFilter.class);

    return http.build();
  }

}

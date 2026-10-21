package com.fairtix.config;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Trusts X-Forwarded-For and X-Forwarded-Proto only from known proxies.
 *
 * <p>Tomcat's {@link RemoteIpValve} walks X-Forwarded-For from the right, skipping
 * trusted proxy addresses, and sets {@code getRemoteAddr()} to the first untrusted
 * one. A client can still prepend anything it likes, but those leftmost entries are
 * never used. When the request arrives from a trusted proxy with
 * {@code X-Forwarded-Proto: https}, {@code isSecure()} becomes true, so Spring
 * Security sends HSTS behind a TLS-terminating load balancer.
 *
 * <p>A request from an untrusted address (a client hitting the app directly) keeps
 * its socket address, and its forwarded headers are ignored.
 *
 * <p>This is Java config rather than {@code server.forward-headers-strategy=native},
 * so the test contexts run the same setup (the test {@code application.properties}
 * shadows the main one). Don't set that property as well, or Tomcat gets two valves.
 */
@Configuration
public class ForwardedHeadersConfig {

    /**
     * Regex of trusted proxy addresses. Blank keeps Tomcat's default: loopback and
     * the private ranges (10/8, 172.16/12, 192.168/16, 169.254/16, and IPv6
     * loopback, link-local and unique-local), which covers a load balancer inside a VPC.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> remoteIpValveCustomizer(
            @Value("${fairtix.proxy.internal-proxies:}") String internalProxies) {
        return factory -> {
            RemoteIpValve valve = new RemoteIpValve();
            valve.setRemoteIpHeader("X-Forwarded-For");
            valve.setProtocolHeader("X-Forwarded-Proto");
            if (!internalProxies.isBlank()) {
                valve.setInternalProxies(internalProxies);
            }
            factory.addEngineValves(valve);
        };
    }
}

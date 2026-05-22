package com.fairtix.branding.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default DNS TXT resolver backed by {@code com.sun.jndi.dns} — ships with the
 * JDK, no new deps. Production-grade resolution (caching, multi-record handling,
 * timeouts) lives in the ops verification worker; this implementation is good
 * enough for the synchronous "check now" UX.
 */
@Component
public class JndiDnsTxtResolver implements CustomDomainService.DnsTxtResolver {

  private static final Logger log = LoggerFactory.getLogger(JndiDnsTxtResolver.class);

  @Override
  public List<String> lookupTxt(String name) {
    Hashtable<String, Object> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
    env.put("com.sun.jndi.dns.timeout.initial", "2000");
    env.put("com.sun.jndi.dns.timeout.retries", "2");
    try {
      DirContext ctx = new InitialDirContext(env);
      try {
        Attributes attrs = ctx.getAttributes(name, new String[] { "TXT" });
        Attribute txt = attrs.get("TXT");
        if (txt == null) return Collections.emptyList();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < txt.size(); i++) {
          Object v = txt.get(i);
          if (v != null) {
            // DNS TXT values arrive wrapped in quotes when multiple strings.
            values.add(v.toString().replaceAll("^\"|\"$", ""));
          }
        }
        return values;
      } finally {
        ctx.close();
      }
    } catch (NamingException e) {
      log.debug("DNS TXT lookup failed for {}: {}", name, e.getMessage());
      return Collections.emptyList();
    }
  }
}

package com.fairtix.branding.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.organizations.application.PublicEndpoint;

/**
 * Serves {@code /embed.js}, the script venues drop onto their own site to
 * render a list of upcoming events (M2-23).
 *
 * <p>Usage:
 * <pre>{@code <script src="https://tickets.fairtix.io/embed.js"
 *           data-org="blue-note-nyc"
 *           data-target="#fairtix-events"></script>}</pre>
 *
 * The widget creates an iframe pointing at the storefront's embed view, then
 * auto-resizes via {@code postMessage} (the storefront posts content-height as
 * its layout changes). Click-through goes to the storefront — not the iframe
 * parent — so Stripe Elements isn't sandboxed inside a third-party origin.
 */
@RestController
@PublicEndpoint("Static script — no auth, served cached.")
public class EmbedScriptController {

  private final String baseUrl;

  public EmbedScriptController(@Value("${fairtix.public-base-url:https://tickets.fairtix.io}") String baseUrl) {
    this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
        ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @GetMapping(value = "/embed.js", produces = "application/javascript")
  public ResponseEntity<String> embedScript() {
    String js = "/* FairTix embed widget */\n"
        + "(function(){\n"
        + "  var base = " + jsString(baseUrl) + ";\n"
        + "  var current = document.currentScript;\n"
        + "  if (!current) return;\n"
        + "  var org = current.getAttribute('data-org');\n"
        + "  if (!org) return;\n"
        + "  var targetSel = current.getAttribute('data-target');\n"
        + "  var height = current.getAttribute('data-height') || '600';\n"
        + "  var target = targetSel ? document.querySelector(targetSel) : null;\n"
        + "  if (!target) {\n"
        + "    target = document.createElement('div');\n"
        + "    current.parentNode.insertBefore(target, current);\n"
        + "  }\n"
        + "  var iframe = document.createElement('iframe');\n"
        + "  iframe.src = base + '/embed/' + encodeURIComponent(org);\n"
        + "  iframe.setAttribute('frameborder','0');\n"
        + "  iframe.setAttribute('scrolling','no');\n"
        + "  iframe.setAttribute('allow','payment');\n"
        + "  iframe.style.width = '100%';\n"
        + "  iframe.style.border = '0';\n"
        + "  iframe.style.height = height + 'px';\n"
        + "  target.appendChild(iframe);\n"
        + "  window.addEventListener('message', function(ev){\n"
        + "    if (typeof ev.data !== 'object' || !ev.data) return;\n"
        + "    if (ev.source !== iframe.contentWindow) return;\n"
        + "    if (ev.data.type === 'fairtix:resize' && typeof ev.data.height === 'number') {\n"
        + "      iframe.style.height = ev.data.height + 'px';\n"
        + "    }\n"
        + "  });\n"
        + "})();\n";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/javascript"))
        .header("Cache-Control", "public, max-age=300")
        .body(js);
  }

  private static String jsString(String s) {
    StringBuilder sb = new StringBuilder("\"");
    if (s != null) {
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '\\': sb.append("\\\\"); break;
          case '"':  sb.append("\\\""); break;
          case '\n': sb.append("\\n");  break;
          case '\r': sb.append("\\r");  break;
          case '<':  sb.append("\\u003C"); break; // safe vs. </script>
          default:   sb.append(c);
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}

package com.fairtix.branding.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownRendererTest {

  /**
   * Selection of payloads from the OWASP XSS Filter Evasion Cheat Sheet,
   * adapted to the markdown surface (descriptions). The renderer must escape
   * all HTML special chars before applying the whitelist, so none of these
   * should survive as executable HTML.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "<script>alert(1)</script>",
      "<img src=x onerror=alert(1)>",
      "<svg onload=alert(1)>",
      "<iframe src=javascript:alert(1)></iframe>",
      "<a href=\"javascript:alert(1)\">click</a>",
      "[click](javascript:alert(1))",
      "[click](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)",
      "[click](vbscript:msgbox(1))",
      "[click](  javascript:alert(1))",
      "<body onload=alert(1)>",
      "<a href=\"&#106;avascript:alert(1)\">x</a>",
      "<<SCRIPT>alert(1);//<</SCRIPT>",
      "<scr<script>ipt>alert(1)</scr</script>ipt>",
      "\"><script>alert(1)</script>",
      "'><script>alert(1)</script>",
      "<a href=\"javascript&#58;alert(1)\">x</a>",
      "![alt](javascript:alert(1))"
  })
  void neverExecutesXssPayloads(String payload) {
    String out = MarkdownRenderer.renderToHtml(payload).toLowerCase();

    // The contract: input HTML is escaped, then a whitelist of safe constructs
    // is reconstructed. So the ONLY unescaped < chars that can appear in the
    // output are from the whitelist: <p>, <br>, <h1..3>, <strong>, <em>,
    // <ul>, <ol>, <li>, <a href="https?://...". Anything else is XSS.
    //
    // Check this by inspecting every unescaped < in the output.
    for (int i = 0; i < out.length(); ) {
      int lt = out.indexOf('<', i);
      if (lt < 0) break;
      String tail = out.substring(lt);
      boolean whitelisted =
          tail.startsWith("<p>") || tail.startsWith("</p>")
          || tail.startsWith("<br>") || tail.startsWith("<br/>")
          || tail.startsWith("<h1>") || tail.startsWith("</h1>")
          || tail.startsWith("<h2>") || tail.startsWith("</h2>")
          || tail.startsWith("<h3>") || tail.startsWith("</h3>")
          || tail.startsWith("<strong>") || tail.startsWith("</strong>")
          || tail.startsWith("<em>") || tail.startsWith("</em>")
          || tail.startsWith("<ul>") || tail.startsWith("</ul>")
          || tail.startsWith("<ol>") || tail.startsWith("</ol>")
          || tail.startsWith("<li>") || tail.startsWith("</li>")
          || tail.startsWith("<a href=\"https://")
          || tail.startsWith("<a href=\"http://")
          || tail.startsWith("</a>");
      assertThat(whitelisted)
          .as("Unwhitelisted HTML emitted at offset %d of: %s", lt, out)
          .isTrue();
      i = lt + 1;
    }

    // Defense in depth: regardless of escaping, the output must never contain
    // a javascript: or vbscript: URI inside an href attribute.
    assertThat(out).doesNotContain("href=\"javascript:");
    assertThat(out).doesNotContain("href='javascript:");
    assertThat(out).doesNotContain("href=\"vbscript:");
    assertThat(out).doesNotContain("href=\"data:");
  }

  @Test
  void escapesRawHtml() {
    String out = MarkdownRenderer.renderToHtml("<script>alert('xss')</script>");
    assertThat(out).doesNotContain("<script>");
    assertThat(out).contains("&lt;script&gt;");
  }

  @Test
  void rendersHeadingsBoldItalicAndLinks() {
    String out = MarkdownRenderer.renderToHtml(
        "# Title\n\nA **bold** and *italic* line with a [link](https://example.com).");
    assertThat(out).contains("<h1>Title</h1>");
    assertThat(out).contains("<strong>bold</strong>");
    assertThat(out).contains("<em>italic</em>");
    assertThat(out).contains("href=\"https://example.com\"");
    assertThat(out).contains("rel=\"noopener noreferrer nofollow\"");
  }

  @Test
  void rejectsJavaScriptLinks() {
    String out = MarkdownRenderer.renderToHtml("[click](javascript:alert(1))");
    assertThat(out).doesNotContain("javascript:");
    assertThat(out).contains("click");
  }

  @Test
  void preservesLists() {
    String out = MarkdownRenderer.renderToHtml("- first\n- second\n\n1. one\n2. two");
    assertThat(out).contains("<ul>").contains("<li>first</li>").contains("<li>second</li>").contains("</ul>");
    assertThat(out).contains("<ol>").contains("<li>one</li>").contains("<li>two</li>").contains("</ol>");
  }

  @Test
  void blankInputReturnsEmpty() {
    assertThat(MarkdownRenderer.renderToHtml(null)).isEmpty();
    assertThat(MarkdownRenderer.renderToHtml("")).isEmpty();
    assertThat(MarkdownRenderer.renderToHtml("   ")).isEmpty();
  }
}

package com.fairtix.branding.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny safe Markdown → HTML renderer for event-page descriptions (M2-20).
 *
 * <p>Why a hand-rolled subset rather than commonmark-java? The project rule is
 * "no new dependencies without approval", and event descriptions don't need
 * full GFM — they need short rich text without an XSS hole. The strategy:
 *
 * <ol>
 *   <li>Escape every special HTML char up front (no raw HTML can survive).</li>
 *   <li>Apply a tightly-scoped whitelist of constructs (headings, paragraphs,
 *       lists, bold/italic, links with safe schemes).</li>
 * </ol>
 *
 * Anything outside the whitelist passes through as plain (escaped) text.
 */
public final class MarkdownRenderer {

  // Inline regexes — applied to already-escaped text, so they can't be turned
  // against us via injection of < or " into pattern boundaries.
  private static final Pattern BOLD   = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
  private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?!\\*)([^*\\n]+)\\*(?!\\*)");
  // After escaping, '"' becomes &quot; — so [text](url) survives but the URL is
  // captured verbatim; we re-validate the URL before emitting an href.
  private static final Pattern LINK   = Pattern.compile("\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)");
  private static final Pattern SAFE_URL = Pattern.compile("^https?://[^\\s<>\"']+$");

  private MarkdownRenderer() {}

  public static String renderToHtml(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);

    StringBuilder out = new StringBuilder();
    List<String> paragraphBuffer = new ArrayList<>();
    String listType = null; // "ul" or "ol" while inside a list

    for (String raw : lines) {
      String line = raw;

      // Heading
      Matcher heading = Pattern.compile("^(#{1,3})\\s+(.+)$").matcher(line);
      if (heading.matches()) {
        flushParagraph(out, paragraphBuffer);
        listType = closeList(out, listType);
        int level = heading.group(1).length();
        out.append("<h").append(level).append('>')
            .append(applyInline(escape(heading.group(2))))
            .append("</h").append(level).append(">\n");
        continue;
      }

      // Unordered list
      Matcher ul = Pattern.compile("^[*\\-]\\s+(.+)$").matcher(line);
      if (ul.matches()) {
        flushParagraph(out, paragraphBuffer);
        if (!"ul".equals(listType)) { listType = closeList(out, listType); out.append("<ul>\n"); listType = "ul"; }
        out.append("  <li>").append(applyInline(escape(ul.group(1)))).append("</li>\n");
        continue;
      }

      // Ordered list
      Matcher ol = Pattern.compile("^\\d+\\.\\s+(.+)$").matcher(line);
      if (ol.matches()) {
        flushParagraph(out, paragraphBuffer);
        if (!"ol".equals(listType)) { listType = closeList(out, listType); out.append("<ol>\n"); listType = "ol"; }
        out.append("  <li>").append(applyInline(escape(ol.group(1)))).append("</li>\n");
        continue;
      }

      // Blank line ends a paragraph or list block
      if (line.isBlank()) {
        flushParagraph(out, paragraphBuffer);
        listType = closeList(out, listType);
        continue;
      }

      // Regular paragraph text
      listType = closeList(out, listType);
      paragraphBuffer.add(line);
    }
    flushParagraph(out, paragraphBuffer);
    closeList(out, listType);

    return out.toString().trim();
  }

  private static void flushParagraph(StringBuilder out, List<String> buf) {
    if (buf.isEmpty()) return;
    StringBuilder p = new StringBuilder();
    for (int i = 0; i < buf.size(); i++) {
      if (i > 0) p.append("<br>");
      p.append(applyInline(escape(buf.get(i))));
    }
    out.append("<p>").append(p).append("</p>\n");
    buf.clear();
  }

  private static String closeList(StringBuilder out, String listType) {
    if (listType != null) out.append("</").append(listType).append(">\n");
    return null;
  }

  private static String applyInline(String escaped) {
    // Order matters: links first (so [foo](url) doesn't get split by italic *),
    // then bold (** before *), then italic.
    String out = LINK.matcher(escaped).replaceAll(m -> {
      String text = m.group(1);
      String url = m.group(2);
      if (!SAFE_URL.matcher(url).matches()) {
        // Drop the link; render the visible text only.
        return Matcher.quoteReplacement(text);
      }
      return Matcher.quoteReplacement(
          "<a href=\"" + url + "\" rel=\"noopener noreferrer nofollow\" target=\"_blank\">"
              + text + "</a>");
    });
    out = BOLD.matcher(out).replaceAll("<strong>$1</strong>");
    out = ITALIC.matcher(out).replaceAll("<em>$1</em>");
    return out;
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&':  sb.append("&amp;");  break;
        case '<':  sb.append("&lt;");   break;
        case '>':  sb.append("&gt;");   break;
        case '"':  sb.append("&quot;"); break;
        case '\'': sb.append("&#39;");  break;
        default:   sb.append(c);
      }
    }
    return sb.toString();
  }
}

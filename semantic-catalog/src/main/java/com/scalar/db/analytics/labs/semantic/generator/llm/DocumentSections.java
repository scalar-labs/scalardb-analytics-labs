/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.generator.llm;

import com.scalar.db.analytics.labs.semantic.generator.GeneratorContext.DocumentObservation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The relevant parts of the domain's documents that one model call reads (see {@code
 * docs/semantic-catalog/generators.md}, "Input passed to external models"). A document is split
 * into sections at its Markdown headings. When every document fits within a size bound, the model
 * reads them whole; otherwise it reads only the sections that name what it is asked about, and the
 * parts left out are recorded as missing.
 */
final class DocumentSections {
  /**
   * How many characters of document text one model call reads at most, an initial bound that keeps
   * the documents a small share of what a model reads.
   */
  static final int CHARACTERS = 20_000;

  private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\r|\\n");

  private DocumentSections() {}

  /**
   * One section of a document: a heading and the lines up to the next heading, or the lines before
   * the first heading.
   *
   * @param heading the heading's text; empty for the lines before the first heading
   * @param text the section's lines as the document has them, its heading line included
   */
  record Section(DocumentObservation document, String heading, String text) {}

  /**
   * The sections a call reads and what is left out.
   *
   * @param missing one entry per document with a part left out
   */
  record Chosen(List<Section> sections, List<String> missing) {
    Chosen {
      sections = List.copyOf(sections);
      missing = List.copyOf(missing);
    }
  }

  /** The sections of every document, in document order, leaving out those with only white space. */
  static List<Section> sections(List<DocumentObservation> documents) {
    var sections = new ArrayList<Section>();
    documents.forEach(document -> split(document, sections));
    return sections;
  }

  private static void split(DocumentObservation document, List<Section> sections) {
    var heading = "";
    var text = new StringBuilder();
    String fence = null;
    for (var line : LINE_BREAK.split(document.document().text(), -1)) {
      var opened = fence(line);
      if (fence == null && opened != null) {
        fence = opened;
      } else if (fence != null && opened != null && opened.equals(fence)) {
        fence = null;
      } else if (fence == null) {
        var title = heading(line);
        if (title != null) {
          add(sections, document, heading, text);
          heading = title;
          text.setLength(0);
        }
      }
      if (!text.isEmpty()) {
        text.append('\n');
      }
      text.append(line);
    }
    add(sections, document, heading, text);
  }

  /** The marker of a code fence a line opens or closes, if it is one. */
  private static @Nullable String fence(String line) {
    var stripped = indented(line);
    if (stripped == null) {
      return null;
    }
    if (stripped.startsWith("```")) {
      return "```";
    }
    return stripped.startsWith("~~~") ? "~~~" : null;
  }

  /** The text of an ATX heading a line is, if it is one; empty for a heading with no text. */
  private static @Nullable String heading(String line) {
    var stripped = indented(line);
    if (stripped == null) {
      return null;
    }
    var level = 0;
    while (level < stripped.length() && stripped.charAt(level) == '#') {
      level++;
    }
    if (level == 0 || level > 6) {
      return null;
    }
    var rest = stripped.substring(level);
    if (!rest.isEmpty() && rest.charAt(0) != ' ' && rest.charAt(0) != '\t') {
      return null;
    }
    var end = rest.length();
    while (end > 0 && Character.isWhitespace(rest.charAt(end - 1))) {
      end--;
    }
    var closing = end;
    while (closing > 0 && rest.charAt(closing - 1) == '#') {
      closing--;
    }
    // A closing sequence of #s counts only when white space precedes it.
    if (closing < end && (closing == 0 || Character.isWhitespace(rest.charAt(closing - 1)))) {
      end = closing;
    }
    return rest.substring(0, end).strip();
  }

  /** A line without the up to three spaces a heading or fence may start with; null if more. */
  private static @Nullable String indented(String line) {
    var spaces = 0;
    while (spaces < line.length() && spaces < 4 && line.charAt(spaces) == ' ') {
      spaces++;
    }
    return spaces > 3 ? null : line.substring(spaces);
  }

  private static void add(
      List<Section> sections, DocumentObservation document, String heading, StringBuilder text) {
    if (!text.toString().isBlank()) {
      sections.add(new Section(document, heading, text.toString()));
    }
  }

  /**
   * Chooses the sections one call reads: every section when all fit within the bound, otherwise the
   * sections naming one of the names, in document order, as far as the bound allows.
   *
   * @param all the sections of every document, as {@link #sections} gives them
   * @param names the names of what the call asks about, such as table and column names, matched
   *     regardless of case where no ASCII letter, digit, or underscore adjoins them, so {@code id}
   *     does not match inside {@code paid} but {@code status} matches beside Japanese text
   */
  static Chosen choose(List<Section> all, Collection<String> names, int bound) {
    var total = all.stream().mapToLong(section -> section.text().length()).sum();
    if (total <= bound) {
      return new Chosen(all, List.of());
    }
    var named =
        names.stream()
            .filter(name -> !name.isBlank())
            .map(
                name ->
                    Pattern.compile(
                        "(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();
    var chosen = new ArrayList<Section>();
    var leftOut = new LinkedHashSet<DocumentObservation>();
    var used = 0L;
    for (var section : all) {
      if (named.stream().anyMatch(name -> name.matcher(section.text()).find())
          && used + section.text().length() <= bound) {
        chosen.add(section);
        used += section.text().length();
      } else {
        leftOut.add(section.document());
      }
    }
    var missing =
        leftOut.stream()
            .map(
                document ->
                    "parts of document "
                        + document.document().name()
                        + " ("
                        + document.inputResource()
                        + ") that name nothing asked about or exceed the bound of "
                        + bound
                        + " characters")
            .toList();
    return new Chosen(chosen, missing);
  }
}

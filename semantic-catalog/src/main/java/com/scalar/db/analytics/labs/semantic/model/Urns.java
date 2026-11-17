/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The form of URNs: {@code urn:scalardb:analytics:<domain>:<record kind>:<identifier>} (see {@code
 * docs/semantic-catalog/record-model.md}, "Identity and URNs", and {@code
 * docs/semantic-catalog/storage.md}, "Identifiers").
 *
 * <p>The domain comes first after the namespace, so a domain identifier is embedded in every URN
 * the domain issues and must keep that form parseable.
 *
 * <p>Elements, Fact Addresses, and Facts get identifiers built from names, so a person can tell
 * what a URN names:
 *
 * <ul>
 *   <li>an element's record kind is its element kind, and its identifier is the canonical form of
 *       its {@linkplain PhysicalCatalogReference Physical Catalog reference} ({@code
 *       dataset:shop.public.orders}, {@code field:shop.public.orders.amount}), the slug its
 *       proposer gave ({@code measure:ec-sales}), or for an edge the identifiers of its ends
 *       ({@code
 *       identifier-correspondence:shop.public.orders.customer_id,shop.public.customers.id});
 *   <li>a Fact Address's identifier is its element's kind and identifier and its predicate ({@code
 *       fact-address:field:shop.public.orders.amount/meaning}), followed by the language for a
 *       language-qualified predicate ({@code fact-address:business-term:ec-sales/written-form;ja});
 *   <li>a Fact's identifier is its Fact Address's identifier and a sequence number ({@code
 *       fact:field:shop.public.orders.amount/meaning/1}).
 * </ul>
 *
 * <p>Where such an identifier is already taken, a sequence number is {@linkplain #numbered
 * appended} ({@code measure:ec-sales~2}). Names are {@linkplain #encode(String) percent-encoded},
 * so a name never contains a separator. The form is a construction choice, not a contract: only the
 * store builds these URNs, once, when it writes the record that creates them, and the log keeps the
 * URN it issued.
 *
 * <p>Input Resources, Input Observations, and log records are identified by the {@linkplain
 * LogPosition position} of a log record ({@code input-observation:c3.1}), and a Catalog Snapshot by
 * its commit number, and a source metadata schema by the SHA-256 digest of its content. A Fact
 * Revision is identified by its Fact and the position of the Fact Event that produced it ({@code
 * fact-revision:field:shop.public.orders.amount/meaning/1@c5.2}).
 */
public final class Urns {
  /** The namespace of every URN; the product defines no abbreviation of its name. */
  public static final String PREFIX = "urn:scalardb:analytics:";

  /**
   * The form of a domain identifier: lowercase letters, digits, and hyphens, starting with a letter
   * or digit, at most 63 characters. It contains no colon, so the domain can be read back from a
   * URN.
   */
  public static final Pattern DOMAIN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

  /**
   * The form of a slug: words of lowercase letters and digits joined by single hyphens, at most 63
   * characters. It needs no encoding and contains no separator.
   */
  public static final Pattern SLUG = Pattern.compile("(?=.{1,63}$)[a-z0-9]+(-[a-z0-9]+)*");

  public static final String FACT_ADDRESS = "fact-address";
  public static final String FACT = "fact";
  public static final String FACT_REVISION = "fact-revision";
  public static final String INPUT_RESOURCE = "input-resource";
  public static final String INPUT_OBSERVATION = "input-observation";
  public static final String LOG_RECORD = "log-record";
  public static final String CATALOG_SNAPSHOT = "catalog-snapshot";
  public static final String SOURCE_METADATA_SCHEMA = "source-metadata-schema";

  private static final Set<String> POSITIONAL =
      Set.of(INPUT_RESOURCE, INPUT_OBSERVATION, LOG_RECORD);
  private static final Set<String> ELEMENT_KINDS =
      Arrays.stream(ElementKind.values()).map(Urns::recordKind).collect(Collectors.toSet());

  private Urns() {}

  /**
   * Checks that a caller-supplied domain identifier has the {@linkplain #DOMAIN required form}.
   *
   * @throws IllegalArgumentException when it does not
   */
  public static void requireDomain(String domain) {
    if (!DOMAIN.matcher(domain).matches()) {
      throw new IllegalArgumentException(
          "A domain identifier consists of lowercase letters, digits, and hyphens: " + domain);
    }
  }

  /**
   * Checks that a proposer's slug has the {@linkplain #SLUG required form}.
   *
   * @throws IllegalArgumentException when it does not
   */
  public static void requireSlug(String slug) {
    if (!SLUG.matcher(slug).matches()) {
      throw new IllegalArgumentException(
          "A slug is lowercase words of letters and digits joined by hyphens: " + slug);
    }
  }

  /**
   * Percent-encodes a name for use in an identifier. Every character other than an ASCII letter, a
   * digit, a hyphen, or an underscore is written as the percent-encoded bytes of its UTF-8 form, so
   * an encoded name never contains a separator.
   */
  public static String encode(String name) {
    var encoded = new StringBuilder();
    for (var b : name.getBytes(StandardCharsets.UTF_8)) {
      var c = (char) (b & 0xff);
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_') {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format(Locale.ROOT, "%02X", (int) c));
      }
    }
    return encoded.toString();
  }

  /** The name an {@linkplain #encode encoded} name stands for. */
  public static String decode(String encoded) {
    // An encoded name holds no '+', so URL decoding reads only its percent-encoded bytes.
    return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
  }

  /** The record kind of an element kind's URNs, such as {@code business-term}. */
  public static String recordKind(ElementKind kind) {
    return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Whether a record kind is that of an element. */
  public static boolean isElementKind(String recordKind) {
    return ELEMENT_KINDS.contains(recordKind);
  }

  /** The URN of an element of a kind, from its name-based identifier. */
  public static String element(String domain, ElementKind kind, String identifier) {
    return PREFIX + domain + ":" + recordKind(kind) + ":" + identifier;
  }

  /**
   * The URN of an edge, from its kind and the URNs of the elements of its ends. The identifiers of
   * one end's elements are sorted; the caller orders the ends of an undirected edge.
   */
  public static String edge(
      String domain, ElementKind kind, Collection<String> fromEnd, Collection<String> toEnd) {
    return element(domain, kind, end(fromEnd) + "," + end(toEnd));
  }

  private static String end(Collection<String> elements) {
    return elements.stream()
        .map(Urns::identifier)
        .sorted()
        .distinct()
        .collect(Collectors.joining("+"));
  }

  /** The URN of the Fact Address of an element and a predicate that is not language-qualified. */
  public static String factAddress(String element, String predicate) {
    return factAddress(element, predicate, null);
  }

  /**
   * The URN of the Fact Address of an element, a predicate, and, for a language-qualified
   * predicate, a language ({@code fact-address:business-term:ec-sales/written-form;ja}). A language
   * tag holds only letters, digits, and hyphens, so it needs no encoding.
   */
  public static String factAddress(String element, String predicate, @Nullable String language) {
    var parsed = parse(element);
    return PREFIX
        + parsed.domain()
        + ":"
        + FACT_ADDRESS
        + ":"
        + parsed.kind()
        + ":"
        + parsed.identifier()
        + "/"
        + encode(predicate)
        + (language == null ? "" : ";" + Languages.requireTag(language));
  }

  /** The URN of the Fact with a sequence number at a Fact Address. */
  public static String fact(String factAddress, int number) {
    var parsed = parse(factAddress);
    requireKind(parsed, factAddress, FACT_ADDRESS);
    return PREFIX + parsed.domain() + ":" + FACT + ":" + parsed.identifier() + "/" + number;
  }

  /**
   * A name-based URN with a sequence number appended, for when the URN itself is already taken.
   *
   * @param number the sequence number, from 2
   */
  public static String numbered(String urn, int number) {
    if (number < 2) {
      throw new IllegalArgumentException("Appended sequence numbers start at 2: " + number);
    }
    return urn + "~" + number;
  }

  /** The URN of a record identified by the position of a log record. */
  public static String of(String domain, String kind, LogPosition position) {
    if (!POSITIONAL.contains(kind)) {
      throw new IllegalArgumentException("Not identified by a log position: " + kind);
    }
    return PREFIX + domain + ":" + kind + ":" + position;
  }

  public static String logRecord(String domain, LogPosition position) {
    return of(domain, LOG_RECORD, position);
  }

  public static String catalogSnapshot(String domain, long commit) {
    return PREFIX + domain + ":" + CATALOG_SNAPSHOT + ":" + commit;
  }

  /**
   * The URN of a source metadata schema, the JSON Schema a connector published for its source
   * metadata documents, identified by the SHA-256 digest of its content.
   */
  public static String sourceMetadataSchema(String domain, String digest) {
    return PREFIX + domain + ":" + SOURCE_METADATA_SCHEMA + ":" + digest;
  }

  /** The URN of the Fact Revision that the Fact Event at a position produced. */
  public static String factRevision(String fact, LogPosition event) {
    var parsed = parse(fact);
    requireKind(parsed, fact, FACT);
    return PREFIX + parsed.domain() + ":" + FACT_REVISION + ":" + parsed.identifier() + "@" + event;
  }

  /** The Fact a Fact Revision belongs to. */
  public static String revisionFact(String revision) {
    var parsed = parse(revision);
    requireKind(parsed, revision, FACT_REVISION);
    var identifier = parsed.identifier();
    return PREFIX
        + parsed.domain()
        + ":"
        + FACT
        + ":"
        + identifier.substring(0, revisionSeparator(identifier, revision));
  }

  /** The log position of the Fact Event that produced a Fact Revision. */
  public static LogPosition revisionEvent(String revision) {
    var parsed = parse(revision);
    requireKind(parsed, revision, FACT_REVISION);
    var identifier = parsed.identifier();
    return LogPosition.parse(identifier.substring(revisionSeparator(identifier, revision) + 1));
  }

  // Names are encoded, so the last '@' always separates the Fact from the event position.
  private static int revisionSeparator(String identifier, String revision) {
    var at = identifier.lastIndexOf('@');
    if (at < 0) {
      throw new IllegalArgumentException("Not a Fact Revision URN: " + revision);
    }
    return at;
  }

  private static void requireKind(Parsed parsed, String urn, String kind) {
    if (!parsed.kind().equals(kind)) {
      throw new IllegalArgumentException("Not a " + kind + " URN: " + urn);
    }
  }

  /** The log position that issued a position-based URN, such as an Input Observation's. */
  public static LogPosition position(String urn) {
    var parsed = parse(urn);
    if (!POSITIONAL.contains(parsed.kind())) {
      throw new IllegalArgumentException("Not identified by one log position: " + urn);
    }
    return LogPosition.parse(parsed.identifier());
  }

  public static String kind(String urn) {
    return parse(urn).kind();
  }

  public static String domain(String urn) {
    return parse(urn).domain();
  }

  /** The identifier, the part after the record kind. */
  public static String identifier(String urn) {
    return parse(urn).identifier();
  }

  private static Parsed parse(String urn) {
    if (!urn.startsWith(PREFIX)) {
      throw new IllegalArgumentException("Not a Semantic Catalog URN: " + urn);
    }
    var parts = urn.substring(PREFIX.length()).split(":", 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException("Not a Semantic Catalog URN: " + urn);
    }
    return new Parsed(parts[0], parts[1], parts[2]);
  }

  private record Parsed(String domain, String kind, String identifier) {}
}

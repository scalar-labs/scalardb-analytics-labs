/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.regex.Pattern;

/**
 * The form of URNs: {@code urn:scalardb:analytics:<domain>:<record kind>:<identifier>} (see {@code
 * docs/semantic-catalog/record-model.md}, "Identity and URNs", and {@code
 * docs/semantic-catalog/storage.md}, "Identifiers").
 *
 * <p>The domain comes first after the namespace, so a domain identifier is embedded in every URN
 * the domain issues and must keep that form parseable.
 *
 * <p>A record created by a log record is identified by that log record's {@linkplain LogPosition
 * position}, such as {@code urn:scalardb:analytics:sales:fact:c3.1}. Log positions are never
 * reused, so the same URNs are issued again when the log is replayed from empty. The identifier
 * carries none of the record's content. A Fact Revision is identified by its Fact and the position
 * of the Fact Event that produced it, such as {@code
 * urn:scalardb:analytics:sales:fact-revision:c3.1@c5.2}.
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

  public static final String ELEMENT = "element";
  public static final String FACT_ADDRESS = "fact-address";
  public static final String FACT = "fact";
  public static final String FACT_REVISION = "fact-revision";
  public static final String INPUT_RESOURCE = "input-resource";
  public static final String INPUT_OBSERVATION = "input-observation";
  public static final String LOG_RECORD = "log-record";
  public static final String CATALOG_SNAPSHOT = "catalog-snapshot";

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

  public static String of(String domain, String kind, LogPosition position) {
    return PREFIX + domain + ":" + kind + ":" + position;
  }

  public static String logRecord(String domain, LogPosition position) {
    return of(domain, LOG_RECORD, position);
  }

  public static String catalogSnapshot(String domain, long commit) {
    return PREFIX + domain + ":" + CATALOG_SNAPSHOT + ":" + commit;
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

  private static int revisionSeparator(String identifier, String revision) {
    var at = identifier.indexOf('@');
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

  /** The log position that issued a position-based URN, such as a Fact or a log record. */
  public static LogPosition position(String urn) {
    var parsed = parse(urn);
    if (parsed.kind().equals(CATALOG_SNAPSHOT) || parsed.kind().equals(FACT_REVISION)) {
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

/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Issues the Semantic Catalog's URNs.
 *
 * <p>Provisional: every URN is {@code urn:sdba:<kind>:<domain>:...}. Callers treat URNs as opaque
 * strings; only this class composes them.
 */
public final class Urns {
  private static final String PREFIX = "urn:sdba:";
  private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-z0-9]+");

  private Urns() {}

  public static String domain(String domain) {
    return PREFIX + "domain:" + domain;
  }

  public static String snapshot(String domain, long commit) {
    return PREFIX + "snapshot:" + domain + ":" + commit;
  }

  /** The commit number of a Catalog Snapshot URN of this domain, or -1 when it is not one. */
  public static long snapshotCommit(String domain, String urn) {
    var prefix = PREFIX + "snapshot:" + domain + ":";
    if (!urn.startsWith(prefix)) {
      return -1;
    }
    try {
      return Long.parseLong(urn.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  public static String tableResource(String domain, String resourceIdentifier) {
    return PREFIX + "resource:" + domain + ":table:" + resourceIdentifier;
  }

  public static String documentResource(String domain) {
    return PREFIX + "resource:" + domain + ":document:" + UUID.randomUUID();
  }

  public static String dataResource(String domain, String check, Object target) {
    return PREFIX + "resource:" + domain + ":data:" + check + ":" + shortDigest(target);
  }

  public static String observation(String domain, String logRecordId) {
    return PREFIX + "observation:" + domain + ":" + logRecordId;
  }

  public static String dataset(String domain, String resourceIdentifier) {
    return PREFIX + "element:" + domain + ":dataset:" + resourceIdentifier;
  }

  public static String field(String datasetUrn, String column) {
    return datasetUrn + "/field:" + column;
  }

  /** A node element other than a Dataset or Field, named by its kind and a slug of its name. */
  public static String node(String domain, ElementKind kind, String name) {
    var slug = SLUG_UNSAFE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
    slug = slug.replaceAll("^-+|-+$", "");
    if (slug.isEmpty()) {
      slug = shortDigest(name);
    }
    return PREFIX + "element:" + domain + ":" + kind.wireName() + ":" + slug;
  }

  /** An edge element, identified by its kind, endpoints, direction, and identifying part. */
  public static String edge(
      String domain,
      ElementKind kind,
      String from,
      String to,
      boolean directed,
      JsonNode identifying) {
    var endpoints =
        directed || from.compareTo(to) <= 0 ? new String[] {from, to} : new String[] {to, from};
    var identity = Json.object();
    identity.put("kind", kind.wireName());
    identity.put("from", endpoints[0]);
    identity.put("to", endpoints[1]);
    identity.put("directed", directed);
    identity.set("identifying", identifying);
    return PREFIX + "element:" + domain + ":" + kind.wireName() + ":" + shortDigest(identity);
  }

  public static String factAddress(String domain, String element, String predicate, Scope scope) {
    var identity = Json.object();
    identity.put("element", element);
    identity.put("predicate", predicate);
    identity.set("scope", Json.tree(scope));
    return PREFIX + "address:" + domain + ":" + shortDigest(identity);
  }

  public static String fact(String domain, String address, String valueNormalForm) {
    return PREFIX + "fact:" + domain + ":" + shortDigest(address + "\n" + valueNormalForm);
  }

  public static String factRevision(String fact, String eventId) {
    return fact + "@" + eventId;
  }

  /** The Fact URN of a Fact Revision URN. */
  public static String factOfRevision(String revision) {
    var at = revision.lastIndexOf('@');
    return at < 0 ? revision : revision.substring(0, at);
  }

  public static String question() {
    return PREFIX + "question:" + UUID.randomUUID();
  }

  private static String shortDigest(Object value) {
    var text = value instanceof String string ? string : Json.canonical(value);
    return Json.sha256(text).substring(0, 16);
  }
}

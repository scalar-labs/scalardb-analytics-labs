/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Scope;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.util.function.Function;

/** One domain under test, with shorthands for the records a caller would write. */
final class Scenario {
  static final JsonNode NOTHING = JsonNodeFactory.instance.objectNode();

  final SemanticCatalogStore store;
  final String domain;
  final Ref policy;

  Scenario(SemanticCatalogStore store) {
    this.store = store;
    this.domain = TestStore.newDomain();
    store.createDomain(domain);
    var request = CommitRequest.builder("set-policy", "owner");
    var version =
        request.add(
            new LogRecord.PolicyVersion(
                JsonNodeFactory.instance.objectNode().put("preset", "auto")));
    this.policy = store.commit(domain, request.build()).ref(version);
  }

  /** Starts building one commit. */
  Commit begin() {
    return new Commit(CommitRequest.builder("test", "tester"));
  }

  CommitResult write(Commit commit) {
    return store.commit(domain, commit.request.build());
  }

  <T> T latest(Function<CatalogSnapshot, T> read) {
    return store.read(domain, Point.LATEST, read);
  }

  long lastCommit() {
    return latest(CatalogSnapshot::commit);
  }

  /** One commit request being built. */
  final class Commit {
    final CommitRequest.Builder request;

    Commit(CommitRequest.Builder request) {
      this.request = request;
    }

    Ref add(LogRecord record) {
      return request.add(record);
    }

    /** A Dataset with its existence and technical-reference Facts. */
    Ref dataset(String table) {
      var dataset =
          add(
              new LogRecord.Element(
                  new ElementIdentity.PhysicalObject(ElementKind.DATASET, null, table)));
      closedRangeFact(address(dataset, Vocabulary.EXISTENCE), "exists");
      closedRangeFact(address(dataset, Vocabulary.TECHNICAL_REFERENCE), table);
      return dataset;
    }

    Ref address(Ref element, String predicate) {
      return add(new LogRecord.FactAddress(element, predicate, Scope.EMPTY, null));
    }

    /** A Fact whose value is the same as another's exactly when the values are equal. */
    Ref closedRangeFact(Ref address, String value) {
      return add(
          new LogRecord.Fact(
              address, JsonNodeFactory.instance.textNode(value), IdentityKey.choice(value)));
    }
  }
}

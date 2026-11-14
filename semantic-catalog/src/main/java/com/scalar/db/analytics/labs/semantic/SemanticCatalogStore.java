/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.SemanticCatalogException;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.projection.CommitResolver;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow;
import com.scalar.db.analytics.labs.semantic.projection.PositionedRecord;
import com.scalar.db.analytics.labs.semantic.projection.Projection;
import com.scalar.db.analytics.labs.semantic.projection.ProjectionSource;
import com.scalar.db.analytics.labs.semantic.projection.Projector;
import com.scalar.db.analytics.labs.semantic.storage.JdbcStore;
import com.scalar.db.analytics.labs.semantic.storage.StoreTransaction;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;

/**
 * The store of the Semantic Catalog and the internal operations on it (see {@code
 * docs/semantic-catalog/storage.md}).
 *
 * <p>Opening the store prepares it for use: it checks that the store is PostgreSQL and applies
 * pending schema changes. There is no separate migration step.
 *
 * <p>Every operation runs in one {@code REPEATABLE READ} transaction and judges only what that
 * transaction read; nothing is cached in the process across transactions. Several processes may
 * open the same store at once. A commit is numbered one past the last commit in the writer's
 * snapshot, so two overlapping writers to one domain collide on that number and the later one runs
 * again from the start on top of the earlier one's commit.
 *
 * <p>A store holds a connection pool and must be {@linkplain #close() closed}.
 */
public final class SemanticCatalogStore implements AutoCloseable {
  private final JdbcStore store;

  private SemanticCatalogStore(JdbcStore store) {
    this.store = store;
  }

  /**
   * Opens the Semantic Catalog's own connection pool to the store and prepares the {@code
   * semantic_catalog} schema.
   *
   * @throws SemanticCatalogException when the store cannot be reached or is not PostgreSQL; the
   *     message names the product found
   */
  public static SemanticCatalogStore open(StoreSettings settings) {
    return new SemanticCatalogStore(
        JdbcStore.open(
            settings.jdbcUrl(),
            settings.username(),
            settings.password(),
            settings.maximumPoolSize()));
  }

  /**
   * Creates a domain under a caller-supplied domain identifier. A domain is the unit of management
   * that assigns one responsible party to a body of data and its semantics, and every other record
   * belongs to exactly one domain.
   *
   * @param domain the domain identifier, in the form {@link Urns#DOMAIN} requires
   * @throws IllegalArgumentException when the identifier does not have that form
   * @throws SemanticCatalogException when the domain already exists
   */
  public void createDomain(String domain) {
    Urns.requireDomain(domain);
    try {
      store.writeOnce(
          transaction -> {
            if (transaction.domainExists(domain)) {
              throw new SemanticCatalogException("Domain " + domain + " already exists");
            }
            transaction.insertDomain(domain);
            return Boolean.TRUE;
          });
    } catch (DuplicateKeyException e) {
      // A concurrent caller created the same domain after this transaction's check.
      throw new SemanticCatalogException("Domain " + domain + " already exists", e);
    }
  }

  /** Whether a domain exists. */
  public boolean domainExists(String domain) {
    return store.read(transaction -> transaction.domainExists(domain));
  }

  /**
   * Writes a request's records to a domain's log as one commit, together with the materialized
   * records they change (see {@code docs/semantic-catalog/storage.md}, "Writes and reads").
   *
   * <p>In one transaction it numbers the commit one past the last commit in its snapshot, replaces
   * temporary numbers with URNs, drops proposals that are the same as existing records, validates
   * and applies the remaining records, and writes the commit, its log records, and the changed
   * materialized rows. When every record is the same as an existing one, nothing is written.
   *
   * @return the commit written and the URN each temporary number stands for
   * @throws SemanticCatalogException when the domain does not exist
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when a record fails
   *     validation; nothing is written then
   */
  public CommitResult commit(String domain, CommitRequest request) {
    return store.write(transaction -> commit(transaction, domain, request));
  }

  private static CommitResult commit(
      StoreTransaction transaction, String domain, CommitRequest request) {
    requireDomain(transaction, domain);
    var commit = transaction.lastCommit(domain) + 1;
    var projection = new Projection(domain, transaction.projectionSource(domain));
    var resolution =
        CommitResolver.resolve(
            domain, commit, request.records(), projection, transaction.identityLookup(domain));
    if (resolution.records().isEmpty()) {
      return new CommitResult(OptionalLong.empty(), resolution.urns());
    }
    // Inserted first, so a concurrent writer of the same number collides as early as possible.
    transaction.insertCommit(domain, commit, request.operation(), request.author());
    var projector = Projector.validating(projection);
    projector.begin(commit);
    resolution.records().forEach(projector::apply);
    projector.end();
    for (var record : resolution.records()) {
      transaction.insertLogRecord(
          domain,
          record,
          targetFact(record),
          targetInputResource(domain, record, projection),
          targetFactAddress(record));
    }
    for (var row : projection.changedRows()) {
      transaction.writeRow(domain, row);
    }
    for (var row : projection.removedRows()) {
      transaction.deleteRow(domain, row);
    }
    return new CommitResult(OptionalLong.of(commit), resolution.urns());
  }

  private static @Nullable String targetFact(PositionedRecord positioned) {
    return switch (positioned.record()) {
      case LogRecord.Fact fact -> fact.issuedUrn();
      case LogRecord.FactEvent event -> event.fact().urnValue();
      default -> null;
    };
  }

  private static @Nullable String targetFactAddress(PositionedRecord positioned) {
    return positioned.record() instanceof LogRecord.ResolveConflict resolution
        ? resolution.factAddress().urnValue()
        : null;
  }

  private static @Nullable String targetInputResource(
      String domain, PositionedRecord positioned, Projection projection) {
    return switch (positioned.record()) {
      case LogRecord.InputResource ignored ->
          Urns.of(domain, Urns.INPUT_RESOURCE, positioned.position());
      case LogRecord.InputObservation observation -> observation.inputResource().urnValue();
      case LogRecord.ObservationEvent event ->
          projection.observationResource(event.observation().urnValue()).orElse(null);
      default -> null;
    };
  }

  /**
   * Reads the Catalog Snapshot of a domain at a point. The reader runs in one read-only {@code
   * REPEATABLE READ} transaction, so everything it reads belongs to one snapshot of the store and
   * does not change if a commit lands meanwhile. The snapshot is valid only inside the reader.
   *
   * @throws SemanticCatalogException when the domain does not exist, or the point names a commit
   *     not written yet
   */
  public <T> T read(String domain, Point point, Function<CatalogSnapshot, T> reader) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          var last = transaction.lastCommit(domain);
          var commit =
              switch (point) {
                case Point.Latest ignored -> last;
                case Point.At at -> {
                  if (at.commit() > last) {
                    throw new SemanticCatalogException(
                        "Domain " + domain + " has no commit " + at.commit() + " yet");
                  }
                  yield at.commit();
                }
              };
          return reader.apply(new CatalogSnapshot(transaction, domain, commit));
        });
  }

  /**
   * Runs the replay check: replays a domain's whole log from empty and compares the result, URNs
   * included, with the materialized records (see {@code docs/semantic-catalog/storage.md}, "Replay
   * check"). It takes time proportional to the log, so it is not run at startup.
   *
   * @throws SemanticCatalogException when the domain does not exist
   */
  public ReplayCheck replayCheck(String domain) {
    return store.read(
        transaction -> {
          requireDomain(transaction, domain);
          var projection = new Projection(domain, ProjectionSource.EMPTY);
          var projector = Projector.replaying(projection);
          var commit = 0L;
          for (var record : transaction.logRecords(domain)) {
            if (record.position().commit() != commit) {
              if (commit != 0) {
                projector.end();
              }
              commit = record.position().commit();
              projector.begin(commit);
            }
            projector.apply(record);
          }
          if (commit != 0) {
            projector.end();
          }
          var replayed = new HashSet<MaterializedRow>(projection.rows().toList());
          var stored = new HashSet<MaterializedRow>(transaction.rows(domain));
          return new ReplayCheck(
              commit,
              replayed.stream().filter(row -> !stored.contains(row)).map(Object::toString).toList(),
              stored.stream()
                  .filter(row -> !replayed.contains(row))
                  .map(Object::toString)
                  .toList());
        });
  }

  private static void requireDomain(StoreTransaction transaction, String domain) {
    if (!transaction.domainExists(domain)) {
      throw new SemanticCatalogException("Unknown domain " + domain);
    }
  }

  /** Closes the connection pool. */
  @Override
  public void close() {
    store.close();
  }
}

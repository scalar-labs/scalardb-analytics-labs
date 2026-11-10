/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Turns a commit request into the log records to write.
 *
 * <p>Records are resolved in request order. Each record that would create something is first
 * matched against existing records by the identity rules (edges, empty-scope Fact Addresses,
 * closed-range Facts, Input Resources, and Datasets and Fields by technical reference), including
 * records earlier in the same request. A matched record is not written, and references to it lead
 * to the existing record. Every other record gets the next position in the commit, and temporary
 * numbers are replaced by the URNs issued from those positions.
 */
public final class CommitResolver {
  private final String domain;
  private final long commit;
  private final Projection projection;
  private final IdentityLookup lookup;
  private final List<Target> targets = new ArrayList<>();
  private final Map<String, String> localIdentities = new HashMap<>();
  private final Map<String, String> localLatestObservations = new HashMap<>();
  private final List<PositionedRecord> written = new ArrayList<>();
  private final Map<Integer, String> urns = new LinkedHashMap<>();

  /**
   * @param records the log records to write, in log order
   * @param urns the URN each temporary number now stands for: an issued URN, or the existing record
   *     a proposed one matched
   */
  public record Resolution(List<PositionedRecord> records, Map<Integer, String> urns) {
    public Resolution {
      records = List.copyOf(records);
      urns = Map.copyOf(urns);
    }
  }

  /**
   * What a temporary number stands for.
   *
   * @param record the URN of the created or matched record
   * @param logRecord the log record written for it, absent when it matched an existing record
   */
  private record Target(@Nullable String record, @Nullable String logRecord) {}

  private CommitResolver(String domain, long commit, Projection projection, IdentityLookup lookup) {
    this.domain = domain;
    this.commit = commit;
    this.projection = projection;
    this.lookup = lookup;
  }

  /**
   * Resolves a commit request into the log records to write.
   *
   * @param commit the number the commit will have
   * @param request the records of the request, in order
   * @param projection the projection the commit will be applied to, which supplies the latest
   *     observations of Input Resources
   * @param lookup finds existing records in the writing transaction's snapshot
   * @throws com.scalar.db.analytics.labs.semantic.model.CommitRejectedException when a reference
   *     cannot be resolved
   */
  public static Resolution resolve(
      String domain,
      long commit,
      List<LogRecord> request,
      Projection projection,
      IdentityLookup lookup) {
    var resolver = new CommitResolver(domain, commit, projection, lookup);
    for (var record : request) {
      try {
        resolver.add(record);
      } catch (IllegalArgumentException e) {
        throw new CommitRejectedException(
            "Request record " + resolver.targets.size() + " is invalid: " + e.getMessage());
      }
    }
    return new Resolution(resolver.written, resolver.urns);
  }

  private void add(LogRecord request) {
    var record = resolveRefs(request);
    var existing = existing(record);
    var index = targets.size();
    if (existing.isPresent()) {
      targets.add(new Target(existing.get(), null));
      urns.put(index, existing.get());
      return;
    }
    var at = new LogPosition(commit, written.size() + 1);
    var logRecord = Urns.logRecord(domain, at);
    var created = createdUrn(record, at);
    targets.add(new Target(created, logRecord));
    urns.put(index, created != null ? created : logRecord);
    identityKey(record).ifPresent(key -> localIdentities.put(key, requireKey(created)));
    if (record instanceof LogRecord.InputObservation observation && created != null) {
      localLatestObservations.put(observation.inputResource().urnValue(), created);
    }
    written.add(new PositionedRecord(at, record));
  }

  private @Nullable String createdUrn(LogRecord record, LogPosition at) {
    var kind =
        switch (record) {
          case LogRecord.Element ignored -> Urns.ELEMENT;
          case LogRecord.FactAddress ignored -> Urns.FACT_ADDRESS;
          case LogRecord.Fact ignored -> Urns.FACT;
          case LogRecord.InputResource ignored -> Urns.INPUT_RESOURCE;
          case LogRecord.InputObservation ignored -> Urns.INPUT_OBSERVATION;
          default -> null;
        };
    return kind == null ? null : Urns.of(domain, kind, at);
  }

  // Identity

  private Optional<String> existing(LogRecord record) {
    var key = identityKey(record);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    var local = localIdentities.get(key.get());
    if (local != null) {
      return Optional.of(local);
    }
    return switch (record) {
      case LogRecord.Element element ->
          switch (element.identity()) {
            case ElementIdentity.PhysicalObject technical ->
                lookup.technicalElement(
                    technical.kind(),
                    IdentityKey.choice(technical.technicalReference()).canonical());
            case ElementIdentity.Edge edge ->
                lookup.edge(edge.kind(), Projection.edgeIdentity(edge));
            case ElementIdentity.Unique ignored -> Optional.empty();
          };
      case LogRecord.FactAddress address ->
          lookup.emptyScopeAddress(address.element().urnValue(), address.predicate());
      case LogRecord.Fact fact ->
          lookup.closedRangeFact(
              fact.address().urnValue(), requireKey(fact.identityKey()).canonical());
      case LogRecord.InputResource resource -> {
        var table = resource.tableIdentifier();
        var question = resource.question();
        yield table != null
            ? lookup.inputResourceByTable(table)
            : lookup.inputResourceByQuestion(requireKey(question));
      }
      default -> Optional.empty();
    };
  }

  /** The key under which a record is the same as another of this request, if it has one. */
  private static Optional<String> identityKey(LogRecord record) {
    return switch (record) {
      case LogRecord.Element element ->
          switch (element.identity()) {
            case ElementIdentity.PhysicalObject technical ->
                Optional.of("technical " + technical.kind() + " " + technical.technicalReference());
            case ElementIdentity.Edge edge -> {
              var identity = Projection.edgeIdentity(edge);
              yield Optional.of(
                  "edge "
                      + edge.kind()
                      + " "
                      + identity.endA()
                      + " "
                      + identity.endB()
                      + " "
                      + identity.directed()
                      + " "
                      + identity.identityKey());
            }
            case ElementIdentity.Unique ignored -> Optional.empty();
          };
      case LogRecord.FactAddress address ->
          address.scope().isEmpty() && address.predicateDefinition() == null
              ? Optional.of("address " + address.element().urnValue() + " " + address.predicate())
              : Optional.empty();
      case LogRecord.Fact fact -> {
        var key = fact.identityKey();
        yield key == null
            ? Optional.empty()
            : Optional.of("fact " + fact.address().urnValue() + " " + key.canonical());
      }
      case LogRecord.InputResource resource -> {
        if (resource.tableIdentifier() != null) {
          yield Optional.of("table " + resource.tableIdentifier());
        }
        yield resource.question() != null
            ? Optional.of("question " + resource.question())
            : Optional.empty();
      }
      default -> Optional.empty();
    };
  }

  private static <T> T requireKey(@Nullable T value) {
    if (value == null) {
      throw new IllegalStateException("An identity key was computed without its value");
    }
    return value;
  }

  // References

  private LogRecord resolveRefs(LogRecord record) {
    UnaryOperator<Ref> toRecord = ref -> resolve(ref, Kind.RECORD);
    return switch (record) {
      case LogRecord.Element element ->
          new LogRecord.Element(
              switch (element.identity()) {
                case ElementIdentity.Unique pointed -> pointed;
                case ElementIdentity.PhysicalObject technical ->
                    new ElementIdentity.PhysicalObject(
                        technical.kind(),
                        technical.parent() == null ? null : toRecord.apply(technical.parent()),
                        technical.technicalReference());
                case ElementIdentity.Edge edge ->
                    new ElementIdentity.Edge(
                        edge.kind(),
                        toRecord.apply(edge.from()),
                        toRecord.apply(edge.to()),
                        edge.directed(),
                        edge.identityKey().mapRefs(toRecord),
                        edge.identifyingPart());
              });
      case LogRecord.FactAddress address ->
          new LogRecord.FactAddress(
              toRecord.apply(address.element()),
              address.predicate(),
              address.scope().mapRefs(toRecord),
              address.predicateDefinition());
      case LogRecord.Fact fact ->
          new LogRecord.Fact(
              toRecord.apply(fact.address()),
              fact.value(),
              fact.identityKey() == null ? null : fact.identityKey().mapRefs(toRecord));
      case LogRecord.InputResource resource -> {
        if (resource.tableIdentifier() != null && resource.question() != null) {
          throw new IllegalArgumentException(
              "An Input Resource is a table's physical schema or a question, not both");
        }
        yield resource;
      }
      case LogRecord.InputObservation observation -> {
        if (observation.previous() != null) {
          throw new IllegalArgumentException(
              "The previous observation is set when the commit is written");
        }
        var resource = toRecord.apply(observation.inputResource());
        var local = localLatestObservations.get(resource.urnValue());
        var previous =
            local != null ? Optional.of(local) : projection.latestObservation(resource.urnValue());
        yield new LogRecord.InputObservation(
            resource, previous.map(Ref::urn).orElse(null), observation.content());
      }
      case LogRecord.ObservationEvent event ->
          new LogRecord.ObservationEvent(toRecord.apply(event.observation()), event.content());
      case LogRecord.PolicyVersion version -> version;
    };
  }

  /** What a reference must lead to. */
  private enum Kind {
    /** The created or matched record. */
    RECORD
  }

  private Ref resolve(Ref ref, Kind kind) {
    return switch (ref) {
      case Ref.Urn urn -> {
        if (!Urns.domain(urn.value()).equals(domain)) {
          throw new IllegalArgumentException("A reference to another domain: " + urn.value());
        }
        yield urn;
      }
      case Ref.Temp temp -> {
        if (temp.number() >= targets.size()) {
          throw new IllegalArgumentException(
              "Temporary number " + temp.number() + " does not name an earlier record");
        }
        var target = targets.get(temp.number());
        var urn =
            switch (kind) {
              case RECORD -> target.record();
            };
        if (urn == null) {
          throw new IllegalArgumentException(
              "Temporary number "
                  + temp.number()
                  + " does not name a "
                  + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        }
        yield Ref.urn(urn);
      }
    };
  }
}

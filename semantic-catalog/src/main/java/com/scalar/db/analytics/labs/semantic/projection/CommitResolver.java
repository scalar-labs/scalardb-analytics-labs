/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.projection;

import com.scalar.db.analytics.labs.semantic.model.Check;
import com.scalar.db.analytics.labs.semantic.model.CommitRejectedException;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.ElementIdentity;
import com.scalar.db.analytics.labs.semantic.model.IdentityKey;
import com.scalar.db.analytics.labs.semantic.model.LogPosition;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.Basis;
import com.scalar.db.analytics.labs.semantic.model.LogRecord.FactEvent;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.projection.MaterializedRow.ElementRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Turns a commit request into the log records to write: the first step of writing a commit, run by
 * {@code SemanticCatalogStore.commit} inside the writing transaction, before the {@link Projector}
 * validates and applies the records.
 *
 * <p>Both what it decides need that transaction. Whether a proposal is the same as an existing
 * record, and whether a URN is already taken, are judged in the transaction's snapshot, and the
 * URNs of Input Resources and Input Observations come from log positions, which exist only once the
 * commit is numbered. A caller therefore refers to the records it creates in the same request, such
 * as a new Fact and its acceptance, by temporary numbers, and only to written records by URN.
 *
 * <p>Records are resolved in request order. Each record that would create something is first
 * matched against existing records by the identity rules (edges, empty-scope Fact Addresses,
 * closed-range Facts, Input Resources, and Datasets and Fields by technical reference), including
 * records earlier in the same request. A matched record is not written, and references to it lead
 * to the existing record. Every other record gets the next position in the commit. A new element,
 * Fact Address, or Fact is issued a name-based URN, which its log record keeps; it is never decided
 * from URNs whether two records are the same. Temporary numbers are replaced by the URNs issued.
 *
 * <p>What a temporary number stands for depends on where it is used. It names the record created or
 * matched where a record is expected, the log record where a basis or a policy version is expected,
 * and the Fact Revision a Fact Event produced where a dependency's revision is expected.
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
  private final Set<String> issued = new HashSet<>();
  private final Map<String, Integer> nextFactNumbers = new HashMap<>();
  private final Map<String, String> localParents = new HashMap<>();

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
   * @param record the URN of the created or matched record; absent for a record that creates
   *     nothing
   * @param logRecord the log record written for it; absent when it matched an existing record
   * @param revision the Fact Revision a Fact Event produced; absent for other records
   */
  private record Target(
      @Nullable String record, @Nullable String logRecord, @Nullable String revision) {}

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
      requireSameParent(record, existing.get());
      targets.add(new Target(existing.get(), null, null));
      urns.put(index, existing.get());
      return;
    }
    var at = new LogPosition(commit, written.size() + 1);
    var logRecord = Urns.logRecord(domain, at);
    String created;
    switch (record) {
      case LogRecord.Named named -> {
        created = issue(named);
        record = withUrn(named, created);
        if (named instanceof LogRecord.Element element
            && element.identity() instanceof ElementIdentity.PhysicalObject technical
            && technical.parent() != null) {
          localParents.put(created, technical.parent().urnValue());
        }
      }
      case LogRecord.InputResource ignored -> created = Urns.of(domain, Urns.INPUT_RESOURCE, at);
      case LogRecord.InputObservation ignored ->
          created = Urns.of(domain, Urns.INPUT_OBSERVATION, at);
      default -> created = null;
    }
    var revision =
        record instanceof FactEvent event ? Urns.factRevision(event.fact().urnValue(), at) : null;
    targets.add(new Target(created, logRecord, revision));
    urns.put(index, created != null ? created : logRecord);
    identityKey(record).ifPresent(key -> localIdentities.put(key, requireKey(created)));
    if (record instanceof LogRecord.InputObservation observation && created != null) {
      localLatestObservations.put(observation.inputResource().urnValue(), created);
    }
    written.add(new PositionedRecord(at, record));
  }

  /**
   * Requires a proposed Field that matched an existing one to name the same parent Dataset. The
   * match is found by technical reference alone, and a matched record is not written, so the
   * projector never checks it against its parent; a proposal under another Dataset would otherwise
   * silently lead to a Field of another table.
   */
  private void requireSameParent(LogRecord record, String existing) {
    if (record instanceof LogRecord.Element element
        && element.identity() instanceof ElementIdentity.PhysicalObject technical
        && technical.parent() != null) {
      var parent = technical.parent().urnValue();
      var existingParent =
          localParents.containsKey(existing)
              ? localParents.get(existing)
              : projection.element(existing).map(ElementRow::parent).orElse(null);
      if (!parent.equals(existingParent)) {
        throw new IllegalArgumentException(
            "Field "
                + existing
                + " for column "
                + technical.technicalReference().canonical()
                + " belongs to Dataset "
                + existingParent
                + ", not to "
                + parent);
      }
    }
  }

  // URNs

  /**
   * Issues the name-based URN of a new element, Fact Address, or Fact: the URN built from its
   * names, or, when that is taken in the store or earlier in the request, the first free one with a
   * sequence number appended. A Fact always has a sequence number. A Fact Address that is not found
   * by its element and predicate always has one too, so the plain identifier stays with the Fact
   * Address that is.
   */
  private String issue(LogRecord.Named record) {
    var urn =
        switch (record) {
          case LogRecord.Element element -> free(elementUrn(element.identity()), true);
          case LogRecord.FactAddress address ->
              free(
                  Urns.factAddress(address.element().urnValue(), address.predicate()),
                  address.foundByPredicate());
          case LogRecord.Fact fact -> {
            // Facts are never deleted and every one at an address is numbered, so the count of
            // Facts at the address is where the free numbers start.
            var address = fact.address().urnValue();
            var number = nextFactNumbers.computeIfAbsent(address, key -> lookup.factCount(key) + 1);
            while (taken(Urns.fact(address, number))) {
              number++;
            }
            nextFactNumbers.put(address, number + 1);
            yield Urns.fact(address, number);
          }
        };
    issued.add(urn);
    return urn;
  }

  private String elementUrn(ElementIdentity identity) {
    return switch (identity) {
      case ElementIdentity.Unique named -> Urns.element(domain, named.kind(), named.slug());
      case ElementIdentity.PhysicalObject technical ->
          Urns.element(domain, technical.kind(), technical.technicalReference().canonical());
      case ElementIdentity.Edge edge -> {
        var from = edge.fromEnd().stream().map(Ref::urnValue).toList();
        var to = edge.toEnd().stream().map(Ref::urnValue).toList();
        var swap =
            !edge.directed()
                && Projection.canonicalEnd(from).compareTo(Projection.canonicalEnd(to)) > 0;
        yield Urns.edge(domain, edge.kind(), swap ? to : from, swap ? from : to);
      }
    };
  }

  /**
   * The URN itself when it is free and may be used plain, or else the first free one with a
   * sequence number appended.
   */
  private String free(String urn, boolean plain) {
    if (plain && !taken(urn)) {
      return urn;
    }
    var number = 2;
    while (taken(Urns.numbered(urn, number))) {
      number++;
    }
    return Urns.numbered(urn, number);
  }

  private boolean taken(String urn) {
    return issued.contains(urn) || lookup.taken(urn);
  }

  private static LogRecord withUrn(LogRecord.Named record, String urn) {
    return switch (record) {
      case LogRecord.Element element -> new LogRecord.Element(urn, element.identity());
      case LogRecord.FactAddress address ->
          new LogRecord.FactAddress(
              urn,
              address.element(),
              address.predicate(),
              address.scope(),
              address.predicateDefinition());
      case LogRecord.Fact fact ->
          new LogRecord.Fact(urn, fact.address(), fact.value(), fact.identityKey());
    };
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
                    IdentityKey.choice(technical.technicalReference().canonical()).canonical());
            case ElementIdentity.Edge edge ->
                lookup.edge(edge.kind(), Projection.edgeIdentity(edge));
            case ElementIdentity.Unique ignored -> Optional.empty();
          };
      case LogRecord.FactAddress address ->
          lookup.emptyScopeAddress(address.element().urnValue(), address.predicate());
      case LogRecord.Fact fact ->
          lookup.closedRangeFact(
              fact.address().urnValue(), requireKey(fact.identityKey()).canonical());
      case LogRecord.InputResource resource ->
          lookup.inputResourceByTable(requireKey(resource.tableIdentifier()));
      default -> Optional.empty();
    };
  }

  /** The key under which a record is the same as another of this request, if it has one. */
  private static Optional<String> identityKey(LogRecord record) {
    return switch (record) {
      case LogRecord.Element element ->
          switch (element.identity()) {
            case ElementIdentity.PhysicalObject technical ->
                Optional.of(
                    "technical "
                        + technical.kind()
                        + " "
                        + technical.technicalReference().canonical());
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
          address.foundByPredicate()
              ? Optional.of("address " + address.element().urnValue() + " " + address.predicate())
              : Optional.empty();
      case LogRecord.Fact fact -> {
        var key = fact.identityKey();
        yield key == null
            ? Optional.empty()
            : Optional.of("fact " + fact.address().urnValue() + " " + key.canonical());
      }
      case LogRecord.InputResource resource ->
          resource.tableIdentifier() == null
              ? Optional.empty()
              : Optional.of("table " + resource.tableIdentifier());
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
    if (record instanceof LogRecord.Named named && named.urn() != null) {
      throw new IllegalArgumentException("The URN of a new record is issued when it is written");
    }
    UnaryOperator<Ref> toRecord = ref -> resolve(ref, Kind.RECORD);
    return switch (record) {
      case LogRecord.Element element ->
          new LogRecord.Element(
              switch (element.identity()) {
                case ElementIdentity.Unique pointed -> pointed;
                case ElementIdentity.PhysicalObject technical ->
                    new ElementIdentity.PhysicalObject(
                        technical.parent() == null ? null : toRecord.apply(technical.parent()),
                        technical.technicalReference());
                case ElementIdentity.Edge edge -> edge.mapRefs(toRecord);
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
      case LogRecord.InputResource resource -> resource;
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
      case LogRecord.Accept accept ->
          new LogRecord.Accept(
              toRecord.apply(accept.fact()),
              dependencies(accept.dependencies()),
              checks(accept.checks()),
              accept.inputsRead(),
              accept.provenance());
      case LogRecord.ProposeRetire proposal ->
          new LogRecord.ProposeRetire(
              toRecord.apply(proposal.fact()),
              proposal.reason(),
              checks(proposal.checks()),
              proposal.inputsRead(),
              proposal.provenance());
      case LogRecord.EvaluatePolicy evaluation ->
          new LogRecord.EvaluatePolicy(
              toRecord.apply(evaluation.fact()),
              resolve(evaluation.policyVersion(), Kind.LOG_RECORD),
              evaluation.transition(),
              evaluation.result(),
              evaluation.decidedBy(),
              evaluation.reason());
      case LogRecord.Answer answer ->
          new LogRecord.Answer(
              toRecord.apply(answer.fact()),
              answer.transition(),
              answer.answerType(),
              answer.decidedBy(),
              answer.authorityCheck(),
              answer.wording(),
              answer.reviewPresentation());
      case LogRecord.Confirm confirm ->
          new LogRecord.Confirm(
              toRecord.apply(confirm.fact()),
              confirm.path(),
              resolve(confirm.basis(), Kind.LOG_RECORD));
      case LogRecord.Reject rejection ->
          new LogRecord.Reject(toRecord.apply(rejection.fact()), basis(rejection.basis()));
      case LogRecord.Retire retirement ->
          new LogRecord.Retire(toRecord.apply(retirement.fact()), basis(retirement.basis()));
      case LogRecord.ResolveConflict resolution ->
          new LogRecord.ResolveConflict(
              toRecord.apply(resolution.factAddress()),
              toRecord.apply(resolution.chosen()),
              resolution.passedOver().stream().map(toRecord).toList(),
              resolution.decidedBy(),
              resolution.authorityCheck(),
              resolution.wording(),
              resolution.reviewPresentation());
    };
  }

  private List<Dependency> dependencies(List<Dependency> dependencies) {
    return dependencies.stream()
        .<Dependency>map(
            dependency ->
                switch (dependency) {
                  case Dependency.OnFact onFact ->
                      new Dependency.OnFact(
                          resolve(onFact.fact(), Kind.RECORD),
                          resolve(onFact.revision(), Kind.REVISION));
                  case Dependency.OnInput onInput ->
                      new Dependency.OnInput(
                          resolve(onInput.inputResource(), Kind.RECORD),
                          resolve(onInput.observation(), Kind.RECORD));
                })
        .toList();
  }

  private List<Check> checks(List<Check> checks) {
    return checks.stream()
        .map(
            check ->
                new Check(
                    check.method(),
                    check.items(),
                    check.result(),
                    check.reference() == null ? null : resolve(check.reference(), Kind.CHECKED),
                    check.details()))
        .toList();
  }

  private Basis basis(Basis basis) {
    return switch (basis) {
      case Basis.Event event -> new Basis.Event(resolve(event.logRecord(), Kind.LOG_RECORD));
      case Basis.Cascade cascade -> new Basis.Cascade(resolve(cascade.fact(), Kind.RECORD));
    };
  }

  /** What a reference must lead to. */
  private enum Kind {
    /** The created or matched record. */
    RECORD,
    /** The log record written. */
    LOG_RECORD,
    /** The Fact Revision a Fact Event produced. */
    REVISION,
    /** What a check is made against: an Input Observation, or a Fact Revision. */
    CHECKED
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
              case LOG_RECORD -> target.logRecord();
              case REVISION -> target.revision();
              case CHECKED -> target.record() != null ? target.record() : target.revision();
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

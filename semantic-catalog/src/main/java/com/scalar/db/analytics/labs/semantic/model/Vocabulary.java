/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The closed vocabularies of the record model (see {@code docs/semantic-catalog/record-model.md},
 * "Elements and predicates"): element kinds, the closed-core predicates of each kind, and the other
 * closed lists the store relies on. What a predicate's values mean is interpreted by Generators and
 * judgement.
 */
public final class Vocabulary {
  /**
   * The existence predicate, which every element has: it states that the element is a target of the
   * catalog, and every other Fact about the element depends on it.
   */
  public static final String EXISTENCE = "existence";

  /**
   * The physical-catalog-reference predicate, by which a Dataset or a Field refers to the physical
   * object its data comes from. It decides whether two Datasets or two Fields are the same element.
   */
  public static final String PHYSICAL_CATALOG_REFERENCE = "physical-catalog-reference";

  /** A Dataset's concept that a row represents. */
  public static final String ROW_CONCEPT = "row-concept";

  public static final String ROW_KIND = "row-kind";
  public static final String ROW_GRAIN = "row-grain";
  public static final String ROW_INCLUSION = "row-inclusion";
  public static final String KEY = "key";

  /**
   * The prefix of a Dataset's uniqueness predicates, which carry their Field set in the predicate:
   * {@code uniqueness:} and the sorted Field URNs joined by commas. They cannot be listed before a
   * Field set is proposed, so they are not among {@link #closedCore closed-core predicates}.
   */
  public static final String UNIQUENESS_PREFIX = "uniqueness:";

  /** The uniqueness value of a Field set that does not repeat. */
  public static final String DOES_NOT_REPEAT = "does-not-repeat";

  /** The uniqueness value of a Field set that repeats. */
  public static final String REPEATS = "repeats";

  /** The uniqueness predicate of a Field set, by its Fields' URNs in any order. */
  public static String uniqueness(java.util.Collection<String> fields) {
    return UNIQUENESS_PREFIX + String.join(",", new java.util.TreeSet<>(fields));
  }

  /** The Field set a uniqueness predicate carries, sorted; empty for another predicate. */
  public static List<String> uniquenessFields(String predicate) {
    if (!predicate.startsWith(UNIQUENESS_PREFIX)) {
      return List.of();
    }
    return List.of(predicate.substring(UNIQUENESS_PREFIX.length()).split(","));
  }

  public static final String MEANING = "meaning";
  public static final String VALUE_KIND = "value-kind";
  public static final String UNIT = "unit";
  public static final String NULL_MEANING = "null-meaning";
  public static final String REPRESENTED_CONCEPT = "represented-concept";

  /** A Field's time meaning, including its time zone. */
  public static final String TIME_MEANING = "time-meaning";

  /** A Measure's base Dataset, whose value is the Dataset's URN. */
  public static final String BASE_DATASET = "base-dataset";

  public static final String AGGREGATION_RULE = "aggregation-rule";

  /** A Measure's filter conditions. */
  public static final String FILTER = "filter";

  public static final String REFERENCE_TIME_FIELD = "reference-time-field";

  /**
   * A Measure's default time granularity: a level of a calendar Dimension, {@code {"dimension":
   * <Dimension URN>, "level": <level>}}, because "monthly" may mean calendar or fiscal month.
   */
  public static final String DEFAULT_TIME_GRANULARITY = "default-time-granularity";

  /** A Dimension's kind, one of {@link DimensionKind}, which decides its other predicates. */
  public static final String DIMENSION_KIND = "dimension-kind";

  /**
   * A Dimension's source Field and classification expression, a predicate of the classification
   * kind, which is not defined yet.
   */
  public static final String CLASSIFICATION = "classification";

  /** A Dimension's source time Field, a predicate of a kind that is not defined yet. */
  public static final String SOURCE_TIME_FIELD = "source-time-field";

  /** A Dimension's levels, coarse to fine: {@code {"levels": [...]}}, a predicate of every kind. */
  public static final String HIERARCHY = "hierarchy";

  /** A calendar Dimension's rule: the wording that defines how it divides time. */
  public static final String CALENDAR_RULE = "calendar-rule";

  /**
   * A business term's name. It is {@linkplain #languageQualified language-qualified}: a term has
   * one name per declared language, each at its own Fact Address.
   */
  public static final String WRITTEN_FORM = "written-form";

  /** A business term's referenced element, whose value is the element's URN. */
  public static final String REFERENCED_ELEMENT = "referenced-element";

  public static final String SET_RELATIONSHIP = "set-relationship";
  public static final String BUSINESS_DEFINITION = "business-definition";

  /**
   * Whether a predicate holds one value per declared language, with the language as part of its
   * Fact Address's identity (see {@code docs/semantic-catalog/languages.md}, "Names: one Fact per
   * language"). Only a business term's written form does.
   */
  public static boolean languageQualified(String predicate) {
    return predicate.equals(WRITTEN_FORM);
  }

  /**
   * A concept relationship's multiplicity, whose value is {@code {"from": <bound>, "to": <bound>}},
   * each one of {@link #MULTIPLICITY_BOUNDS}: {@code to} is how many instances of the end the
   * relationship's identity goes to one instance of the end it starts from relates to, and {@code
   * from} the reverse. A customer placing orders is {@code {"from": "exactly-one", "to":
   * "zero-or-more"}} from customer to order.
   */
  public static final String MULTIPLICITY = "multiplicity";

  /** The bounds of a concept relationship's multiplicity at each end. */
  public static final List<String> MULTIPLICITY_BOUNDS =
      List.of("zero-or-one", "exactly-one", "zero-or-more", "one-or-more");

  /** The relationship kind of an undirected concept relationship: both denote the same object. */
  public static final String SAME_OBJECT = "same-object";

  /** The relationship kind of a directed concept relationship between different objects. */
  public static final String ASSOCIATION = "association";

  /** The validation item of a predicate whose value is not made of parts: the value itself. */
  public static final String VALUE_ITEM = "value";

  private Vocabulary() {}

  /**
   * The closed-core predicates of an element kind, existence first: what construction aims at for
   * every element of the kind, so a request for all predicates reports each one without a Fact
   * Address as missing knowledge. For a Dimension these are the predicates of every Dimension;
   * {@link #closedCore(DimensionKind)} adds those of its kind.
   */
  public static List<String> closedCore(ElementKind kind) {
    return switch (kind) {
      case DATASET ->
          List.of(
              EXISTENCE,
              PHYSICAL_CATALOG_REFERENCE,
              ROW_CONCEPT,
              ROW_KIND,
              ROW_GRAIN,
              ROW_INCLUSION,
              KEY);
      case FIELD ->
          List.of(
              EXISTENCE,
              PHYSICAL_CATALOG_REFERENCE,
              MEANING,
              VALUE_KIND,
              UNIT,
              NULL_MEANING,
              REPRESENTED_CONCEPT,
              TIME_MEANING);
      case CONCEPT -> List.of(EXISTENCE, MEANING);
      case MEASURE ->
          List.of(
              EXISTENCE,
              MEANING,
              BASE_DATASET,
              AGGREGATION_RULE,
              FILTER,
              REFERENCE_TIME_FIELD,
              DEFAULT_TIME_GRANULARITY);
      case DIMENSION -> List.of(EXISTENCE, MEANING, DIMENSION_KIND, HIERARCHY);
      case BUSINESS_TERM -> List.of(EXISTENCE, WRITTEN_FORM, REFERENCED_ELEMENT);
      case IDENTIFIER_CORRESPONDENCE -> List.of(EXISTENCE, SET_RELATIONSHIP, BUSINESS_DEFINITION);
      case CONCEPT_RELATIONSHIP -> List.of(EXISTENCE, MULTIPLICITY);
    };
  }

  /**
   * The closed-core predicates of a Dimension of a kind: those of every Dimension, then those of
   * its kind.
   */
  public static List<String> closedCore(DimensionKind kind) {
    var predicates = new ArrayList<>(closedCore(ElementKind.DIMENSION));
    predicates.addAll(kind.predicates());
    return List.copyOf(predicates);
  }

  /**
   * The kinds of Dimension (see {@code docs/semantic-catalog/record-model.md}, "Elements and
   * predicates"). A kind decides which predicates a Dimension has besides those of every Dimension,
   * and the forms of their values; a new kind of Dimension is a new constant here, with the forms
   * of its values in {@code DimensionValues.check}, which does not compile until it covers the
   * kind.
   */
  public enum DimensionKind {
    /**
     * A calendar computed from a time value by a rule, such as a fiscal year starting in April. It
     * is bound to no time Field: a Measure's reference time Field decides which time it divides.
     */
    CALENDAR("calendar", List.of(CALENDAR_RULE));

    private final String value;
    private final List<String> predicates;

    DimensionKind(String value, List<String> predicates) {
      this.value = value;
      this.predicates = predicates;
    }

    /** The value of a dimension-kind Fact that states this kind. */
    public String value() {
      return value;
    }

    /** The predicates of a Dimension of this kind besides those of every Dimension. */
    public List<String> predicates() {
      return predicates;
    }

    /** The kind a dimension-kind value states, if it states one. */
    public static java.util.Optional<DimensionKind> of(String value) {
      for (var kind : values()) {
        if (kind.value.equals(value)) {
          return java.util.Optional.of(kind);
        }
      }
      return java.util.Optional.empty();
    }
  }

  /**
   * The validation items of a predicate on an element: the parts of its value that checks examine
   * (see {@code docs/semantic-catalog/record-model.md}, "Validation results and dependencies"). An
   * identifier correspondence's existence has one item per pair of corresponding Fields ({@code
   * pair:<from Field>=<to Field>}), per normalization step ({@code normalization:<Field>:<step>}),
   * and, when the condition goes through intermediate Datasets, per hop ({@code hop:<index>}); an
   * aggregation rule has its aggregate function and its value expression; any other predicate has
   * the value itself.
   *
   * @param identity the identity of an edge, which holds a correspondence's matching condition;
   *     absent for a node
   */
  public static List<String> validationItems(@Nullable ElementIdentity identity, String predicate) {
    if (predicate.equals(AGGREGATION_RULE)) {
      return List.of("function", "expression");
    }
    if (!predicate.equals(EXISTENCE)
        || !(identity instanceof ElementIdentity.IdentifierCorrespondence correspondence)) {
      return List.of(VALUE_ITEM);
    }
    var items = new ArrayList<String>();
    var hops = correspondence.condition().hops();
    for (var i = 0; i < hops.size(); i++) {
      if (hops.size() > 1) {
        items.add("hop:" + i);
      }
      for (var pair : hops.get(i).pairs()) {
        items.add("pair:" + pair.from().field().urnValue() + "=" + pair.to().field().urnValue());
        for (var side : List.of(pair.from(), pair.to())) {
          for (var step : side.normalization()) {
            items.add(
                "normalization:"
                    + side.field().urnValue()
                    + ":"
                    + step.name().toLowerCase(Locale.ROOT).replace('_', '-'));
          }
        }
      }
    }
    return items;
  }

  /** The kinds of element: the node kinds and the edge kinds of the closed core. */
  public enum ElementKind {
    DATASET(false),
    FIELD(false),
    CONCEPT(false),
    MEASURE(false),
    DIMENSION(false),
    BUSINESS_TERM(false),
    IDENTIFIER_CORRESPONDENCE(true),
    CONCEPT_RELATIONSHIP(true);

    private final boolean edge;

    ElementKind(boolean edge) {
      this.edge = edge;
    }

    public boolean edge() {
      return edge;
    }

    /** Whether elements of this kind are identified by the physical object they refer to. */
    public boolean physicalCatalog() {
      return this == DATASET || this == FIELD;
    }
  }

  /**
   * A value normalization step an identifier correspondence may apply to one side of a pair of
   * Fields before comparing them (see {@code docs/semantic-catalog/record-model.md}, "Elements and
   * predicates"). The list is closed, never a free expression, because the matching condition is
   * part of the edge's identity: two free expressions of the same normalization would make one
   * correspondence two edges, and a Query Agent turns a listed step into SQL without interpreting
   * it.
   */
  public enum Normalization {
    /** Converts letters to lower case. */
    LOWER_CASE,
    /** Removes leading and trailing white space. */
    TRIM,
    /** Applies Unicode normalization form NFKC, which also folds full-width characters. */
    UNICODE_NFKC,
    /** Converts the value to its text form. */
    TO_TEXT,
    /** Removes every character that is not a digit. */
    DIGITS_ONLY
  }

  /** The kinds of Input Resource, which decide how a resource is identified. */
  public enum InputKind {
    /**
     * One table's physical schema, identified by the Physical Catalog's table identifier, so every
     * registration of the same table observes the same resource.
     */
    PHYSICAL_SCHEMA,
    /** One registered document, which is new at registration and observed again on each change. */
    DOCUMENT,
    /**
     * One piece of usage feedback from a user's LLM session: missing knowledge or a statement,
     * including the wording of a correction. Feedback is never revised, so each registration is a
     * new resource with exactly one observation.
     */
    USAGE_FEEDBACK,
    /**
     * The reads of one kind of data check on one target, a Field set or a correspondence,
     * identified by the check and the target, so that each observation can be compared with its
     * predecessor (see {@code docs/semantic-catalog/generators.md}, "The Generator contract").
     * Observations are written with the Generator results that read them and create no build tasks.
     */
    DATA_OBSERVATION
  }

  /**
   * The purpose of the session usage feedback came from. The same statement is a premise of an
   * answer in one and a judgement on a candidate in the other.
   */
  public enum SessionPurpose {
    /** Composing a query with the catalog's knowledge. */
    COMPOSING_A_QUERY,
    /** Constructing the catalog, including corrections given in review. */
    CONSTRUCTING_THE_CATALOG
  }

  /**
   * The state of a Fact Record, defined by which Fact Events it accepts next (see {@code
   * docs/semantic-catalog/record-model.md}, "Fact Events and states").
   */
  public enum FactState {
    /** Accepted but not confirmed. */
    CANDIDATE,
    /** Confirmed knowledge. */
    CONFIRMED,
    /** Judged not to be confirmed. */
    REJECTED,
    /** Confirmed knowledge judged no longer usable. */
    RETIRED
  }

  /** What a policy evaluation or an answer is about: confirming a candidate or retiring a Fact. */
  public enum Transition {
    CONFIRM,
    RETIRE
  }

  /**
   * What a policy evaluation decided: that machine judgement proceeds, or that consent is needed.
   */
  public enum PolicyResult {
    MACHINE_JUDGEMENT,
    REQUEST_CONSENT
  }

  /**
   * The part of a user policy version that decides when machine judgement adopts a candidate or a
   * retirement (see {@code docs/semantic-catalog/construction.md}, "Choosing the confirmation
   * path").
   */
  public enum Preset {
    /** Machine judgement always adopts. */
    FULL_AUTO,
    /**
     * Inline evaluation adopts what deterministic signals cover; the policy evaluation model
     * decides the rest, except that a refuted candidate or a second Fact always goes to a person.
     */
    AUTO,
    /** Machine judgement never adopts. */
    MANUAL
  }

  /**
   * What a data check's result rests on. Data can refute a rule but not establish one, so a support
   * resting on no counterexample found does not on its own let inline evaluation adopt a value (see
   * {@code docs/semantic-catalog/generators.md}, "What data establishes").
   */
  public enum DataFinding {
    /** The check found a counterexample, such as a repeating Field set or an unmatched value. */
    COUNTEREXAMPLE_FOUND,
    /** The check found no counterexample in what it read. */
    NO_COUNTEREXAMPLE_FOUND
  }

  /** The type of a human answer. */
  public enum AnswerType {
    CONSENT,
    REJECTION,
    CORRECTION,
    UNKNOWN
  }

  /** How a candidate was confirmed. */
  public enum ConfirmationPath {
    MACHINE_JUDGEMENT,
    HUMAN_CONSENT
  }

  /** Why a retirement is proposed. */
  public enum RetirementReason {
    ELEMENT_NO_LONGER_EXISTS,
    SOURCE_DECLARATION_NO_LONGER_EXISTS,
    CONFIRMATION_WAS_WRONG
  }

  /**
   * How a check examined its validation items (see {@code docs/semantic-catalog/record-model.md},
   * "Validation results and dependencies").
   */
  public enum ValidationMethod {
    DECLARED_ENFORCED,
    DECLARED_UNENFORCED,
    DATA_CHECK,
    DOCUMENT,
    INFERENCE
  }

  /** What a check found for the validation items it covered. */
  public enum CheckResult {
    SUPPORTED,
    REFUTED,
    UNDECIDABLE
  }
}

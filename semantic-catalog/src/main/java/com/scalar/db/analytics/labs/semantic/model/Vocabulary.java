/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.model;

/**
 * The closed vocabularies of the record model that the store itself relies on (see {@code
 * docs/semantic-catalog/record-model.md}, "Elements and predicates"). The rest of the closed core,
 * such as the predicates of each element kind, is interpreted by Generators and judgement.
 */
public final class Vocabulary {
  /**
   * The existence predicate, which every element has: it states that the element is a target of the
   * catalog, and every other Fact about the element depends on it.
   */
  public static final String EXISTENCE = "existence";

  /**
   * The technical-reference predicate, by which a Dataset or a Field refers to the physical object
   * its data comes from. It decides whether two Datasets or two Fields are the same element.
   */
  public static final String TECHNICAL_REFERENCE = "technical-reference";

  private Vocabulary() {}

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
    public boolean technical() {
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
    USAGE_FEEDBACK
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

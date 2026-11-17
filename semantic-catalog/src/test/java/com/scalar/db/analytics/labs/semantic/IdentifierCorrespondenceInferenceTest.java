/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import static com.scalar.db.analytics.labs.semantic.EcPosSample.drain;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.element;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.facts;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.missingInputs;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.only;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.registerDocument;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.registerPos;
import static com.scalar.db.analytics.labs.semantic.EcPosSample.registerShop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Identifier correspondence inference on the EC/POS sample, whose e-commerce customers and store
 * members share e-mail addresses stored in different letter case, with a glossary stating that they
 * are compared ignoring case (see {@code docs/semantic-catalog/generators.md}, "Meaning
 * inference"). The model is scripted per Generator; the data lives in an in-memory DuckDB.
 */
class IdentifierCorrespondenceInferenceTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final String ROW = "row-meaning-inference";
  private static final String FIELD = "field-meaning-inference";
  private static final String CONCEPT = "concept-inference";
  private static final String CORRESPONDENCE = "identifier-correspondence-inference";

  private static final String SAME_PERSON = "EC の顧客と店舗の会員は、登録メールアドレスが一致すれば同一人物とみなす。";
  private static final String IGNORE_CASE = "店舗会員のメールアドレスは店頭で手入力されるため、大文字と小文字を区別せずに比較する。";
  private static final String GLOSSARY =
      """
      # 販売ドメイン用語集

      ## 顧客 (customers, members)

      %s
      %s
      """
          .formatted(SAME_PERSON, IGNORE_CASE);

  private static final String CUSTOMER_EMAIL = "shop.public.customers.email";
  private static final String MEMBER_EMAIL = "pos.public.members.email";

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  private DuckDbConstructionSql sample() {
    var engine = EcPosSample.data();
    engines.add(engine);
    return engine;
  }

  // Scripts

  /** Every identifying column of the sample, e-mail addresses included, stands for a customer. */
  private static final Map<String, String> ONE_CONCEPT =
      Map.of(
          "shop.public.customers.id",
          "customer",
          CUSTOMER_EMAIL,
          "customer",
          "shop.public.orders.customer_id",
          "customer",
          "pos.public.members.member_id",
          "customer",
          MEMBER_EMAIL,
          "customer",
          "pos.public.store_sales.member_id",
          "customer");

  /**
   * Answers row and Field meaning for every target, and the concept of each column a map names; a
   * concept not in use is declared once per call, and the relationships given are proposed when
   * both their concepts are named.
   */
  private static ScriptedModel concepts(Map<String, String> concepts, List<String[]> sameObject) {
    return new ScriptedModel()
        .answering(ROW, input -> everyTarget(input, "row-kind", "current-state"))
        .answering(FIELD, input -> everyTarget(input, "meaning", "a column of the sample"))
        .answering(
            CONCEPT,
            input -> {
              var inUse = new HashSet<String>();
              input.path("concepts").forEach(concept -> inUse.add(concept.path("ref").asText()));
              var declared = new HashSet<String>();
              var answer = JSON.objectNode();
              var conceptNodes = answer.putArray("concepts");
              var targetNodes = answer.putArray("targets");
              Function<String, String> use =
                  slug -> {
                    if (!inUse.contains(slug) && declared.add(slug)) {
                      conceptNodes
                          .addObject()
                          .put("slug", slug)
                          .putObject("meaning")
                          .put("value", "a " + slug + " of the shop")
                          .put("basis", "the column names");
                    }
                    return slug;
                  };
              for (var target : input.path("targets")) {
                var ref = target.path("ref").asText();
                var slug = concepts.get(ref);
                if (slug != null) {
                  targetNodes
                      .addObject()
                      .put("ref", ref)
                      .putObject("concept")
                      .put("value", use.apply(slug))
                      .put("basis", "the column names");
                }
              }
              var relationships = answer.putArray("relationships");
              for (var pair : sameObject) {
                var named = new HashSet<>(declared);
                named.addAll(inUse);
                if (named.contains(pair[0]) && named.contains(pair[1])) {
                  var multiplicity =
                      relationships
                          .addObject()
                          .put("from", pair[0])
                          .put("to", pair[1])
                          .put("kind", "same-object")
                          .putObject("multiplicity");
                  multiplicity
                      .putObject("value")
                      .put("from", "zero-or-one")
                      .put("to", "zero-or-one");
                  multiplicity.put("basis", "the glossary");
                }
              }
              return answer.toString();
            });
  }

  private static String everyTarget(JsonNode input, String predicate, String value) {
    var answer = JSON.objectNode();
    var targets = answer.putArray("targets");
    for (var target : input.path("targets")) {
      var values = targets.addObject().put("ref", target.path("ref").asText()).putObject("values");
      values.putObject(predicate).put("value", value).put("basis", "its name");
    }
    return answer.toString();
  }

  /** A correspondence the script answers, from one column to another, each side normalized. */
  private record Proposal(String from, List<String> fromSteps, String to, List<String> toSteps) {
    static Proposal lowerCased(String from, String to) {
      return new Proposal(from, List.of("lower-case"), to, List.of("lower-case"));
    }
  }

  /**
   * Answers correspondence inference with the proposals whose columns are both among the call's
   * datasets, citing the glossary when the call read it, or with every proposal when {@code always}
   * is set.
   */
  private static Function<JsonNode, String> correspondences(
      boolean always, List<Proposal> proposals) {
    return input -> {
      var refs = new HashSet<String>();
      for (var dataset : input.path("datasets")) {
        dataset.path("fields").forEach(field -> refs.add(field.path("ref").asText()));
      }
      var documents = input.path("documents");
      var document = documents.isEmpty() ? null : documents.get(0).path("document").asText();
      var answer = JSON.objectNode();
      var nodes = answer.putArray("correspondences");
      for (var proposal : proposals) {
        if (!always && !(refs.contains(proposal.from()) && refs.contains(proposal.to()))) {
          continue;
        }
        var node = nodes.addObject();
        var pair =
            node.putArray("pairs")
                .addObject()
                .put("from", proposal.from())
                .put("to", proposal.to());
        proposal.fromSteps().forEach(pair.putArray("fromNormalization")::add);
        proposal.toSteps().forEach(pair.putArray("toNormalization")::add);
        supported(node.putObject("existence"), "the glossary", document, IGNORE_CASE);
        supported(node.putObject("definition"), "the glossary", document, SAME_PERSON)
            .put("value", "EC の顧客と店舗の会員は、登録メールアドレスで同一人物と判定する。");
      }
      return answer.toString();
    };
  }

  private static ObjectNode supported(
      ObjectNode node, String basis, @Nullable String document, String quotation) {
    node.put("basis", basis);
    if (document != null) {
      node.putObject("citation").put("document", document).put("quotation", quotation);
    }
    return node;
  }

  // Reading what was recorded

  private static String field(Scenario s, String ref) {
    return element(s, ElementKind.FIELD, ref);
  }

  private static String correspondence(Scenario s, String from, String to) {
    return Urns.edge(
        s.domain,
        ElementKind.IDENTIFIER_CORRESPONDENCE,
        List.of(field(s, from)),
        List.of(field(s, to)));
  }

  private static List<JsonNode> calls(ScriptedModel model) {
    return model.calls.stream()
        .filter(call -> call.generator().equals(CORRESPONDENCE))
        .map(ScriptedModel.Call::input)
        .toList();
  }

  private static List<String> items(JsonNode items) {
    var found = new ArrayList<String>();
    items.forEach(item -> found.add(item.asText()));
    return found;
  }

  // Scenarios

  @Test
  void customersAndMembersCorrespondThroughLowerCasedEmailAndTheSetRelationshipCheckExaminesIt() {
    var s = scenario();
    var glossary = registerDocument(s, "glossary.md", GLOSSARY);
    registerShop(s);
    registerPos(s);
    var policy = new EcPosSample.Adopting();
    var model =
        concepts(ONE_CONCEPT, List.of())
            .answering(
                CORRESPONDENCE,
                correspondences(false, List.of(Proposal.lowerCased(CUSTOMER_EMAIL, MEMBER_EMAIL))));

    drain(s, policy, sample(), model);

    // Every call is told to write in the domain's recording language.
    assertThat(calls(model))
        .isNotEmpty()
        .allSatisfy(input -> assertThat(input.path("recordingLanguage").asText()).isEqualTo("ja"));
    // One call passed the customer Fields of both sources with the glossary.
    assertThat(calls(model))
        .anySatisfy(
            input -> {
              assertThat(input.path("concepts").toString()).contains("\"customer\"");
              assertThat(input.path("datasets").toString())
                  .contains("\"ref\":\"" + CUSTOMER_EMAIL + "\"")
                  .contains("\"ref\":\"" + MEMBER_EMAIL + "\"");
              assertThat(input.path("documents")).isNotEmpty();
            });

    var edge = correspondence(s, CUSTOMER_EMAIL, MEMBER_EMAIL);
    var existence = only(s, edge, Vocabulary.EXISTENCE);
    var customerEmail = field(s, CUSTOMER_EMAIL);
    var memberEmail = field(s, MEMBER_EMAIL);
    assertThat(existence.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT);
              assertThat(check.reference()).isEqualTo(Ref.urn(glossary));
              assertThat(items(check.items()))
                  .containsExactlyInAnyOrder(
                      "pair:" + customerEmail + "=" + memberEmail,
                      "normalization:" + customerEmail + ":lower-case",
                      "normalization:" + memberEmail + ":lower-case");
            });
    // The correspondence rests on the represented concepts that put the Fields side by side.
    var customerConcept = only(s, customerEmail, Vocabulary.REPRESENTED_CONCEPT);
    assertThat(existence.dependencies())
        .anySatisfy(
            dependency ->
                assertThat(dependency)
                    .isInstanceOfSatisfying(
                        Dependency.OnFact.class,
                        on -> assertThat(on.fact()).isEqualTo(Ref.urn(customerConcept.fact()))));

    var definition = only(s, edge, Vocabulary.BUSINESS_DEFINITION);
    assertThat(definition.value().asText()).contains("登録メールアドレス");
    assertThat(definition.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT));

    // Neither is adopted inline; once the existence is confirmed, the set relationship check
    // examines the lower-cased values, and only it proposes a set relationship.
    assertThat(policy.asked).contains(existence.fact(), definition.fact());
    assertThat(existence.state()).isEqualTo(FactState.CONFIRMED);
    assertThat(facts(s, edge, Vocabulary.SET_RELATIONSHIP))
        .isNotEmpty()
        .allSatisfy(
            fact ->
                assertThat(fact.checks())
                    .allSatisfy(
                        check -> assertThat(check.method()).isEqualTo(ValidationMethod.DATA_CHECK)))
        .anySatisfy(fact -> assertThat(fact.value().asText()).isEqualTo("equal"));
  }

  @Test
  void aCorrespondenceThatExistsInEitherDirectionIsNotProposedAgain() {
    var s = scenario();
    var sql = sample();
    registerDocument(s, "glossary.md", GLOSSARY);
    registerShop(s);
    registerPos(s);
    var model =
        concepts(ONE_CONCEPT, List.of())
            .answering(
                CORRESPONDENCE,
                correspondences(false, List.of(Proposal.lowerCased(CUSTOMER_EMAIL, MEMBER_EMAIL))));
    drain(s, new EcPosSample.Adopting(), sql, model);
    var edge = correspondence(s, CUSTOMER_EMAIL, MEMBER_EMAIL);
    assertThat(only(s, edge, Vocabulary.EXISTENCE).state()).isEqualTo(FactState.CONFIRMED);
    var before = calls(model).size();

    // A revised glossary asks again; the model, shown the correspondence, proposes it once more
    // as it is and in reverse.
    model.answering(
        CORRESPONDENCE,
        correspondences(
            false,
            List.of(
                Proposal.lowerCased(CUSTOMER_EMAIL, MEMBER_EMAIL),
                Proposal.lowerCased(MEMBER_EMAIL, CUSTOMER_EMAIL))));
    registerDocument(s, "glossary.md", GLOSSARY + "\n顧客は何度でも注文できる。\n");
    drain(s, new EcPosSample.Adopting(), sql, model);

    var later = calls(model).subList(before, calls(model).size());
    assertThat(later)
        .isNotEmpty()
        .allSatisfy(
            input ->
                assertThat(input.path("correspondences").toString())
                    .contains("\"from\":\"" + CUSTOMER_EMAIL + "\"")
                    .contains("\"state\":\"confirmed\""));
    assertThat(facts(s, edge, Vocabulary.EXISTENCE)).hasSize(1);
    assertThat(facts(s, edge, Vocabulary.BUSINESS_DEFINITION)).hasSize(1);
    assertThat(facts(s, correspondence(s, MEMBER_EMAIL, CUSTOMER_EMAIL), Vocabulary.EXISTENCE))
        .isEmpty();
  }

  @Test
  void conceptsLinkedAsTheSameObjectPutTheirFieldsSideBySide() {
    var s = scenario();
    registerDocument(s, "glossary.md", GLOSSARY);
    registerShop(s);
    registerPos(s);
    var concepts =
        Map.of(
            "shop.public.customers.id",
            "customer",
            CUSTOMER_EMAIL,
            "customer",
            "pos.public.members.member_id",
            "member",
            MEMBER_EMAIL,
            "member",
            "pos.public.store_sales.member_id",
            "shopper");
    // Three Concepts of one object, whose relationships close a cycle.
    var sameObject =
        List.of(
            new String[] {"customer", "member"},
            new String[] {"member", "shopper"},
            new String[] {"customer", "shopper"});
    var model =
        concepts(concepts, sameObject)
            .answering(
                CORRESPONDENCE,
                correspondences(false, List.of(Proposal.lowerCased(CUSTOMER_EMAIL, MEMBER_EMAIL))));

    drain(s, new EcPosSample.Adopting(), sample(), model);

    var link =
        Urns.edge(
            s.domain,
            ElementKind.CONCEPT_RELATIONSHIP,
            List.of(element(s, ElementKind.CONCEPT, "customer")),
            List.of(element(s, ElementKind.CONCEPT, "member")));
    var linkExistence = only(s, link, Vocabulary.EXISTENCE);
    assertThat(linkExistence.state()).isEqualTo(FactState.CONFIRMED);
    for (var ends : List.of(List.of("customer", "shopper"), List.of("member", "shopper"))) {
      var closing =
          Urns.edge(
              s.domain,
              ElementKind.CONCEPT_RELATIONSHIP,
              List.of(element(s, ElementKind.CONCEPT, ends.get(0))),
              List.of(element(s, ElementKind.CONCEPT, ends.get(1))));
      assertThat(only(s, closing, Vocabulary.EXISTENCE).state()).isEqualTo(FactState.CONFIRMED);
    }
    var existence = only(s, correspondence(s, CUSTOMER_EMAIL, MEMBER_EMAIL), Vocabulary.EXISTENCE);
    assertThat(existence.dependencies())
        .anySatisfy(
            dependency ->
                assertThat(dependency)
                    .isInstanceOfSatisfying(
                        Dependency.OnFact.class,
                        on -> assertThat(on.fact()).isEqualTo(Ref.urn(linkExistence.fact()))));
  }

  @Test
  void anAnswerThatBreaksTheRulesOfACorrespondenceIsMissingInput() {
    var s = scenario();
    registerShop(s);
    registerPos(s);
    var model =
        concepts(ONE_CONCEPT, List.of())
            .answering(
                CORRESPONDENCE,
                correspondences(
                    true,
                    List.of(
                        // Both ends in one table.
                        new Proposal(
                            "shop.public.customers.id", List.of(), CUSTOMER_EMAIL, List.of()),
                        // A normalization not on the closed list.
                        new Proposal(
                            CUSTOMER_EMAIL, List.of("upper-case"), MEMBER_EMAIL, List.of()),
                        // A column the call did not pass.
                        new Proposal(
                            "shop.public.orders.amount", List.of(), MEMBER_EMAIL, List.of()))));

    var summary = drain(s, new EcPosSample.Adopting(), sample(), model);

    assertThat(calls(model)).isNotEmpty();
    assertThat(missingInputs(summary, CORRESPONDENCE))
        .anySatisfy(missing -> assertThat(missing).contains("in different tables"))
        .anySatisfy(
            missing ->
                assertThat(missing)
                    .contains(CUSTOMER_EMAIL + "=" + MEMBER_EMAIL)
                    .contains("a normalization not listed"))
        .anySatisfy(missing -> assertThat(missing).contains("shop.public.orders.amount"));
    for (var edge :
        Set.of(
            correspondence(s, "shop.public.customers.id", CUSTOMER_EMAIL),
            correspondence(s, CUSTOMER_EMAIL, MEMBER_EMAIL),
            correspondence(s, "shop.public.orders.amount", MEMBER_EMAIL))) {
      assertThat(facts(s, edge, Vocabulary.EXISTENCE)).isEmpty();
    }
  }
}

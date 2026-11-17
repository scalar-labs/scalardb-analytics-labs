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
import com.scalar.db.analytics.labs.semantic.model.BuildTask;
import com.scalar.db.analytics.labs.semantic.model.Dependency;
import com.scalar.db.analytics.labs.semantic.model.LogRecord;
import com.scalar.db.analytics.labs.semantic.model.Ref;
import com.scalar.db.analytics.labs.semantic.model.Urns;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.AnswerType;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.FactState;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.Transition;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ValidationMethod;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Concept inference on the EC/POS sample: an e-commerce source (customers and their orders) and a
 * point-of-sale source (members and their store sales), with a glossary stating that EC customers
 * and store members are the same people (see {@code docs/semantic-catalog/generators.md}, "Meaning
 * inference"). The model is scripted per Generator; the data lives in an in-memory DuckDB.
 */
class ConceptInferenceTest extends StoreScenarioTest {
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final String ROW = "row-meaning-inference";
  private static final String FIELD = "field-meaning-inference";
  private static final String CONCEPT = "concept-inference";

  private static final String GLOSSARY =
      """
      # 販売ドメイン用語集

      ## 顧客 (customers, members)

      EC の顧客と店舗の会員は、登録メールアドレスが一致すれば同一人物とみなす。
      顧客は何度でも注文できる。
      """;

  private final List<DuckDbConstructionSql> engines = new ArrayList<>();

  @AfterEach
  void closeEngines() throws SQLException {
    for (var engine : engines) {
      engine.close();
    }
  }

  // The sample

  private DuckDbConstructionSql sample() {
    var engine = EcPosSample.data();
    engines.add(engine);
    return engine;
  }

  // Scripts

  /** Answers row and Field meaning for every target, so that each is confirmed. */
  private static ScriptedModel meanings() {
    return new ScriptedModel()
        .answering(ROW, input -> everyTarget(input, "row-kind", "event"))
        .answering(FIELD, input -> everyTarget(input, "meaning", "a column of the sample"));
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

  /** The concept each sample table's rows and each identifying column stand for. */
  private static final Map<String, String> CONCEPTS =
      Map.of(
          "shop.public.customers", "customer",
          "pos.public.members", "customer",
          "shop.public.orders", "order",
          "shop.public.customers.id", "customer",
          "shop.public.orders.customer_id", "customer",
          "pos.public.members.member_id", "customer",
          "pos.public.store_sales.member_id", "customer");

  private static final Map<String, String> MEANINGS =
      Map.of(
          "customer", "A person who buys from the shop, online or in a store",
          "order", "One purchase placed online");

  /**
   * Answers concept inference as a model following its instructions would: a concept in use is
   * referred to by its ref, and a new one is declared once per call. The glossary is cited for the
   * customers and members tables when the call reads it.
   */
  private static Function<JsonNode, String> concepts(Set<String> omitted) {
    return input -> {
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
                  .put("value", MEANINGS.get(slug))
                  .put("basis", "the table names");
            }
            return slug;
          };
      for (var target : input.path("targets")) {
        var ref = target.path("ref").asText();
        var slug = CONCEPTS.get(ref);
        if (slug == null || omitted.contains(ref)) {
          continue;
        }
        var concept =
            targetNodes
                .addObject()
                .put("ref", ref)
                .putObject("concept")
                .put("value", use.apply(slug))
                .put("basis", "the table and column names");
        if (ref.equals("shop.public.customers") || ref.equals("pos.public.members")) {
          var documents = input.path("documents");
          if (!documents.isEmpty()) {
            concept
                .putObject("citation")
                .put("document", documents.get(0).path("document").asText())
                .put("quotation", "EC の顧客と店舗の会員は、登録メールアドレスが一致すれば同一人物とみなす。");
          }
        }
      }
      if (declared.contains("order") || inUse.contains("order")) {
        var multiplicity =
            answer
                .putArray("relationships")
                .addObject()
                .put("from", use.apply("customer"))
                .put("to", "order")
                .put("kind", "association")
                .putObject("multiplicity");
        multiplicity.putObject("value").put("from", "exactly-one").put("to", "zero-or-more");
        multiplicity.put("basis", "a customer may place any number of orders");
      }
      return answer.toString();
    };
  }

  // Reading what was recorded

  private static List<JsonNode> calls(ScriptedModel model) {
    return model.calls.stream()
        .filter(call -> call.generator().equals(CONCEPT))
        .map(ScriptedModel.Call::input)
        .toList();
  }

  private static List<String> targetRefs(JsonNode input) {
    return StreamSupport.stream(input.path("targets").spliterator(), false)
        .map(target -> target.path("ref").asText())
        .toList();
  }

  // Scenarios

  @Test
  void customersAndMembersReferToOneConceptThatThePolicyEvaluationModelDecides() {
    var s = scenario();
    var glossary = registerDocument(s, "glossary.md", GLOSSARY);
    registerShop(s);
    registerPos(s);
    var policy = new EcPosSample.Adopting();
    var model = meanings().answering(CONCEPT, concepts(Set.of()));

    drain(s, policy, sample(), model);

    var customer = element(s, ElementKind.CONCEPT, "customer");
    assertThat(only(s, customer, Vocabulary.MEANING).value().asText())
        .isEqualTo(MEANINGS.get("customer"));
    // Every call is told to write in the domain's recording language.
    assertThat(calls(model))
        .isNotEmpty()
        .allSatisfy(input -> assertThat(input.path("recordingLanguage").asText()).isEqualTo("ja"));
    // A row concept is asked with the Dataset's columns in view, targets or not.
    assertThat(calls(model))
        .filteredOn(input -> targetRefs(input).contains("shop.public.customers"))
        .allSatisfy(
            input ->
                assertThat(input.path("datasets").toString())
                    .contains("\"ref\":\"shop.public.customers.email\""));
    // One business concept, however many calls and runs referred to it.
    assertThat(facts(s, element(s, ElementKind.CONCEPT, "customer~2"), Vocabulary.EXISTENCE))
        .isEmpty();

    var customers = element(s, ElementKind.DATASET, "shop.public.customers");
    var members = element(s, ElementKind.DATASET, "pos.public.members");
    var customersConcept = only(s, customers, Vocabulary.ROW_CONCEPT);
    assertThat(customersConcept.value().asText()).isEqualTo(customer);
    assertThat(only(s, members, Vocabulary.ROW_CONCEPT).value().asText()).isEqualTo(customer);
    assertThat(
            only(
                    s,
                    element(s, ElementKind.FIELD, "shop.public.orders.customer_id"),
                    Vocabulary.REPRESENTED_CONCEPT)
                .value()
                .asText())
        .isEqualTo(customer);

    // The reference rests on the Concept's existence, and the glossary it quotes is a document
    // check.
    var customerExistence = only(s, customer, Vocabulary.EXISTENCE);
    assertThat(customersConcept.dependencies())
        .anySatisfy(
            dependency ->
                assertThat(dependency)
                    .isInstanceOfSatisfying(
                        Dependency.OnFact.class,
                        on -> assertThat(on.fact()).isEqualTo(Ref.urn(customerExistence.fact()))));
    assertThat(customersConcept.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.method()).isEqualTo(ValidationMethod.DOCUMENT);
              assertThat(check.reference()).isEqualTo(Ref.urn(glossary));
            });

    // Neither the Concept nor the references are adopted inline: the policy evaluation model
    // decided each.
    assertThat(policy.asked).contains(customerExistence.fact(), customersConcept.fact());
    assertThat(customersConcept.state()).isEqualTo(FactState.CONFIRMED);

    // Customers place orders: an association with the multiplicity the business allows.
    var order = element(s, ElementKind.CONCEPT, "order");
    var relationship =
        Urns.edge(s.domain, ElementKind.CONCEPT_RELATIONSHIP, List.of(customer), List.of(order));
    assertThat(only(s, relationship, Vocabulary.EXISTENCE).state()).isEqualTo(FactState.CONFIRMED);
    assertThat(only(s, relationship, Vocabulary.MULTIPLICITY).value())
        .isEqualTo(JSON.objectNode().put("from", "exactly-one").put("to", "zero-or-more"));
    assertThat(only(s, order, Vocabulary.EXISTENCE).state()).isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void aLaterSourceRefersToTheConceptInUseInsteadOfCreatingAnother() {
    var s = scenario();
    var sql = sample();
    registerShop(s);
    var model = meanings().answering(CONCEPT, concepts(Set.of()));
    drain(s, new EcPosSample.Adopting(), sql, model);
    var customer = element(s, ElementKind.CONCEPT, "customer");
    assertThat(only(s, customer, Vocabulary.EXISTENCE).state()).isEqualTo(FactState.CONFIRMED);
    var before = calls(model).size();

    registerPos(s);
    drain(s, new EcPosSample.Adopting(), sql, model);

    // The model read the concepts in use and the slugs taken.
    var later = calls(model).subList(before, calls(model).size());
    assertThat(later)
        .anySatisfy(
            input -> {
              assertThat(targetRefs(input)).contains("pos.public.members");
              assertThat(input.path("slugsInUse").toString()).contains("\"customer\"");
              assertThat(input.path("concepts").toString()).contains("\"ref\":\"customer\"");
            });
    var members = element(s, ElementKind.DATASET, "pos.public.members");
    assertThat(only(s, members, Vocabulary.ROW_CONCEPT).value().asText()).isEqualTo(customer);
    assertThat(facts(s, element(s, ElementKind.CONCEPT, "customer~2"), Vocabulary.EXISTENCE))
        .isEmpty();
  }

  @Test
  void aSlugAlreadyTakenIsNotGivenToANewConcept() {
    var s = scenario();
    // A person rejected a concept "customer" earlier: it is no longer in use, but its slug stays
    // taken.
    var c = s.begin();
    var rejected = c.concept("customer");
    var existence = new Ref.Temp(((Ref.Temp) rejected).number() + 2);
    var answer = c.answer(existence, Transition.CONFIRM, AnswerType.REJECTION);
    c.add(new LogRecord.Reject(existence, new LogRecord.Basis.Event(answer)));
    s.write(c);
    registerShop(s);
    var model = meanings().answering(CONCEPT, concepts(Set.of()));

    var summary = drain(s, new EcPosSample.Adopting(), sample(), model);

    // The model was told the slug is taken; declaring it anyway creates nothing.
    assertThat(calls(model))
        .allSatisfy(
            input -> {
              assertThat(input.path("slugsInUse").toString()).contains("\"customer\"");
              assertThat(input.path("concepts").toString()).doesNotContain("customer");
            });
    assertThat(facts(s, element(s, ElementKind.CONCEPT, "customer~2"), Vocabulary.EXISTENCE))
        .isEmpty();
    assertThat(
            facts(
                s,
                element(s, ElementKind.DATASET, "shop.public.customers"),
                Vocabulary.ROW_CONCEPT))
        .isEmpty();
    assertThat(missingInputs(summary, CONCEPT))
        .contains("concept customer, whose slug is already taken");
    // A concept not taken is still created.
    assertThat(only(s, element(s, ElementKind.CONCEPT, "order"), Vocabulary.EXISTENCE).state())
        .isEqualTo(FactState.CONFIRMED);
  }

  @Test
  void anUndirectedRelationshipNamedBothWaysInOneRunGetsOneMultiplicity() {
    var s = scenario();
    registerShop(s);
    var model =
        meanings()
            .answering(
                CONCEPT,
                input -> {
                  var answer = JSON.objectNode();
                  var concepts = answer.putArray("concepts");
                  for (var slug : List.of("customer", "buyer")) {
                    concepts
                        .addObject()
                        .put("slug", slug)
                        .putObject("meaning")
                        .put("value", "someone who buys")
                        .put("basis", "the names");
                  }
                  var targets = answer.putArray("targets");
                  for (var ref : targetRefs(input)) {
                    var slug =
                        switch (ref) {
                          case "shop.public.customers" -> "customer";
                          case "shop.public.orders.customer_id" -> "buyer";
                          default -> null;
                        };
                    if (slug != null) {
                      targets
                          .addObject()
                          .put("ref", ref)
                          .putObject("concept")
                          .put("value", slug)
                          .put("basis", "the names");
                    }
                  }
                  var relationships = answer.putArray("relationships");
                  for (var pair :
                      List.of(
                          List.of("customer", "buyer", "exactly-one", "zero-or-one"),
                          List.of("buyer", "customer", "zero-or-one", "exactly-one"))) {
                    var multiplicity =
                        relationships
                            .addObject()
                            .put("from", pair.get(0))
                            .put("to", pair.get(1))
                            .put("kind", "same-object")
                            .putObject("multiplicity");
                    multiplicity.putObject("value").put("from", pair.get(2)).put("to", pair.get(3));
                    multiplicity.put("basis", "the names");
                  }
                  return answer.toString();
                });

    drain(s, new EcPosSample.Adopting(), sample(), model);

    var customer = element(s, ElementKind.CONCEPT, "customer");
    var buyer = element(s, ElementKind.CONCEPT, "buyer");
    var edge =
        Urns.edge(s.domain, ElementKind.CONCEPT_RELATIONSHIP, List.of(buyer), List.of(customer));
    // The first wording is recorded; its mirror in the same run adds no second candidate.
    assertThat(only(s, edge, Vocabulary.MULTIPLICITY).value())
        .isEqualTo(JSON.objectNode().put("from", "exactly-one").put("to", "zero-or-one"));
  }

  @Test
  void aNewDocumentObservationAsksAboutTheDatasetsItNames() {
    var s = scenario();
    var sql = sample();
    registerShop(s);
    registerPos(s);
    // The model leaves the members table undecided at first.
    var model = meanings().answering(CONCEPT, concepts(Set.of("pos.public.members")));
    drain(s, new EcPosSample.Adopting(), sql, model);
    var members = element(s, ElementKind.DATASET, "pos.public.members");
    assertThat(facts(s, members, Vocabulary.ROW_CONCEPT)).isEmpty();
    var before = calls(model).size();

    var glossary = registerDocument(s, "glossary.md", GLOSSARY);
    assertThat(store.buildTasks(s.domain))
        .filteredOn(task -> task.status() instanceof BuildTask.Status.Created)
        .filteredOn(task -> task.work().equals(new BuildTask.Work.GeneratorRun(CONCEPT)))
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.triggers())
                  .containsExactly(Urns.logRecord(s.domain, Urns.position(glossary)));
            });

    model.answering(CONCEPT, concepts(Set.of()));
    drain(s, new EcPosSample.Adopting(), sql, model);

    // The glossary names the customers and members tables, so the run asks about what is still
    // open there and nothing about the tables it does not name.
    var later = calls(model).subList(before, calls(model).size());
    assertThat(later)
        .singleElement()
        .satisfies(
            input ->
                assertThat(targetRefs(input))
                    .contains("pos.public.members")
                    .doesNotContain("shop.public.customers")
                    .allMatch(
                        ref ->
                            ref.startsWith("pos.public.members")
                                || ref.startsWith("shop.public.customers.")));
    assertThat(only(s, members, Vocabulary.ROW_CONCEPT).value().asText())
        .isEqualTo(element(s, ElementKind.CONCEPT, "customer"));
  }
}

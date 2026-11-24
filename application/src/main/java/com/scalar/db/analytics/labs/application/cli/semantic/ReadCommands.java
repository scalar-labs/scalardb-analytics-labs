/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.application.cli.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.scalar.db.analytics.labs.application.ApplicationFailure;
import com.scalar.db.analytics.labs.application.semantic.SemanticCatalogService;
import com.scalar.db.analytics.labs.semantic.LanguageRead;
import com.scalar.db.analytics.labs.semantic.model.Hierarchy;
import com.scalar.db.analytics.labs.semantic.model.Knowledge;
import com.scalar.db.analytics.labs.semantic.model.KnowledgeRequest;
import com.scalar.db.analytics.labs.semantic.model.Review;
import com.scalar.db.analytics.labs.semantic.model.ReviewPresentation;
import com.scalar.db.analytics.labs.semantic.model.Vocabulary.ElementKind;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The Semantic Catalog reads (see {@code docs/semantic-catalog/public-api.md}, "Reads"). Each reads
 * one Catalog Snapshot, the latest unless {@code --snapshot} names one, and returns its URN.
 */
final class ReadCommands {
  private ReadCommands() {}

  /** A read at a point. */
  abstract static class SnapshotRead extends SemanticCall {
    @Option(
        names = "--snapshot",
        paramLabel = "URN",
        description = "Read at this Catalog Snapshot; the latest commit when absent.")
    private @Nullable String snapshot;

    SnapshotRead(Map<String, String> environment) {
      super(environment);
    }

    @Nullable String snapshot() {
      return snapshot;
    }
  }

  /**
   * A read whose text can come with translations: {@code --language} names the language the reader
   * reads, and the output adds the translations beside the unchanged result.
   */
  abstract static class TranslatableRead extends SnapshotRead {
    // Written as the map's declared type, so each text carries its status.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectWriter TEXTS =
        OBJECT_MAPPER.writerFor(new TypeReference<Map<String, LanguageRead.Text>>() {});

    @Option(
        names = "--language",
        paramLabel = "TAG",
        description =
            "Also return each text's translation into this declared language, or a pending mark,"
                + " beside the unchanged originals; originals only when absent.")
    private @Nullable String language;

    TranslatableRead(Map<String, String> environment) {
      super(environment);
    }

    @Nullable String language() {
      return language;
    }

    /** The output with the read's translations, when it named a language, after its fields. */
    static Map<String, Object> withTranslations(
        Map<String, Object> output, SemanticCatalogService.Read<?> read) {
      read.translations()
          .ifPresent(
              translations -> {
                output.put("language", translations.language());
                output.put("recordingLanguage", translations.recordingLanguage());
                output.put("texts", texts(translations.texts()));
              });
      return output;
    }

    private static JsonNode texts(Map<String, LanguageRead.Text> texts) {
      try {
        return OBJECT_MAPPER.readTree(TEXTS.writeValueAsString(texts));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("A read's texts are not writable as JSON", e);
      }
    }
  }

  /** A position in the hierarchy, named by data source, namespace, and table. */
  static final class PositionOptions {
    @Option(names = "--source", paramLabel = "NAME", description = "A data source.")
    @Nullable String source;

    @Option(
        names = "--namespace",
        paramLabel = "NAME",
        description = "A namespace of the data source.")
    @Nullable String namespace;

    @Option(names = "--table", paramLabel = "NAME", description = "A table of the namespace.")
    @Nullable String table;

    static Review.Position of(@Nullable PositionOptions options) {
      if (options == null) {
        return Review.Position.ROOT;
      }
      return SemanticCall.convert(
          options, given -> new Review.Position(given.source, given.namespace, given.table));
    }
  }

  @Command(
      name = "read",
      description =
          "Reads along the hierarchy: a position with its direct children and counts, or with"
              + " --recursive every table below it with the knowledge of each element.")
  static final class ReadCommand extends TranslatableRead {
    @picocli.CommandLine.ArgGroup(exclusive = false)
    private @Nullable PositionOptions position;

    @Option(names = "--recursive", description = "Read every table unit below the position.")
    private boolean recursive;

    @Option(names = "--unconfirmed", description = "Include unconfirmed knowledge.")
    private boolean unconfirmed;

    ReadCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var read =
          service.read(
              service.point(snapshot()),
              PositionOptions.of(position),
              recursive ? Hierarchy.Depth.RECURSIVE : Hierarchy.Depth.CHILDREN,
              view(unconfirmed),
              language());
      return withTranslations(
          envelope(
              "semantic-read", "catalogSnapshot", read.catalogSnapshot(), "level", read.result()),
          read);
    }
  }

  @Command(
      name = "elements",
      description =
          "Finds the elements whose existence is selected, by kind, parent, or business term.")
  static final class ElementsCommand extends SnapshotRead {
    @Option(
        names = "--kind",
        paramLabel = "KIND",
        description = "An element kind, such as measure.")
    private @Nullable String kind;

    @Option(names = "--parent", paramLabel = "URN", description = "The parent element.")
    private @Nullable String parent;

    @Option(
        names = "--term",
        paramLabel = "TEXT",
        description = "A business term's written form, matched exactly in any language.")
    private @Nullable String term;

    @Option(
        names = "--language",
        paramLabel = "TAG",
        description = "With --term, match only the names in this declared language.")
    private @Nullable String language;

    ElementsCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var point = service.point(snapshot());
      var elementKind = kind == null ? null : constant(ElementKind.class, kind, "--kind");
      if (term == null && language != null) {
        throw new ApplicationFailure("INVALID_REQUEST", "--language goes with --term.");
      }
      var read =
          term == null
              ? service.elements(point, elementKind, parent)
              : service.elements(point, elementKind, parent, term, language);
      return envelope(
          "semantic-elements",
          "catalogSnapshot",
          read.catalogSnapshot(),
          "elements",
          read.result());
    }
  }

  @Command(
      name = "knowledge",
      description =
          "Requests knowledge: one predicate, or every predicate, of an element; or a batch of"
              + " requests, including edges by kind and ends, from a JSON file.")
  static final class KnowledgeCommand extends TranslatableRead {
    private final InputStream standardInput;

    @Option(names = "--element", paramLabel = "URN", description = "The element to read.")
    private @Nullable String element;

    @Option(
        names = "--predicate",
        paramLabel = "PREDICATE",
        description = "One predicate of --element; every predicate when absent.")
    private @Nullable String predicate;

    @Option(
        names = "--file",
        paramLabel = "FILE",
        description =
            "A JSON object {\"requests\": [...]} of requests, each {\"element\": URN} or"
                + " {\"edge\": {\"kind\": KIND, \"from\": [URN...], \"to\": [URN...]}}, with an"
                + " optional \"predicate\"; - reads standard input.")
    private @Nullable Path file;

    @Option(names = "--unconfirmed", description = "Include unconfirmed knowledge.")
    private boolean unconfirmed;

    KnowledgeCommand(InputStream standardInput, Map<String, String> environment) {
      super(environment);
      this.standardInput = standardInput;
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var requests = requests();
      var read =
          service.knowledge(service.point(snapshot()), requests, view(unconfirmed), language());
      return withTranslations(
          envelope(
              "semantic-knowledge",
              "catalogSnapshot",
              read.catalogSnapshot(),
              "answers",
              read.result()),
          read);
    }

    private List<KnowledgeRequest> requests() {
      if ((element == null) == (file == null)) {
        throw new ApplicationFailure("INVALID_REQUEST", "Give either --element or --file.");
      }
      if (element != null) {
        return List.of(
            new KnowledgeRequest(new KnowledgeRequest.Target.Element(element), predicate));
      }
      if (predicate != null) {
        throw new ApplicationFailure(
            "INVALID_REQUEST", "--predicate goes with --element; give each request its own.");
      }
      var input = readJson(Objects.requireNonNull(file), standardInput).get("requests");
      if (input == null || !input.isArray() || input.isEmpty()) {
        throw new ApplicationFailure(
            "INVALID_REQUEST", "The input needs a non-empty \"requests\" array.");
      }
      var requests = new ArrayList<KnowledgeRequest>();
      for (var request : input) {
        requests.add(request(request));
      }
      return requests;
    }

    private static KnowledgeRequest request(JsonNode request) {
      var where = "A request";
      var predicate = optionalText(request, "predicate", where);
      var edge = request.get("edge");
      if (edge == null) {
        return new KnowledgeRequest(
            new KnowledgeRequest.Target.Element(text(request, "element", where)), predicate);
      }
      var kind = constant(ElementKind.class, text(edge, "kind", "An edge"), "edge kind");
      return convert(
          edge,
          given ->
              new KnowledgeRequest(
                  new KnowledgeRequest.Target.Edge(kind, urns(given, "from"), urns(given, "to")),
                  predicate));
    }

    private static List<String> urns(JsonNode edge, String end) {
      var urns = edge.get(end);
      if (urns == null || !urns.isArray()) {
        throw new ApplicationFailure(
            "INVALID_REQUEST", "An edge needs the URN array \"" + end + "\".");
      }
      var values = new ArrayList<String>();
      for (var urn : urns) {
        if (!urn.isTextual()) {
          throw new ApplicationFailure(
              "INVALID_REQUEST", "An edge's \"" + end + "\" holds URNs as text.");
        }
        values.add(urn.textValue());
      }
      return values;
    }
  }

  @Command(
      name = "fact",
      description =
          "Retrieves a Fact Revision's content by its URN, or a Fact's latest revision at the"
              + " point by the Fact URN.")
  static final class FactCommand extends TranslatableRead {
    @Parameters(index = "0", paramLabel = "URN", description = "A Fact Revision or Fact URN.")
    private @Nullable String urn;

    FactCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var read = service.fact(service.point(snapshot()), Objects.requireNonNull(urn), language());
      return withTranslations(
          envelope(
              "semantic-fact", "catalogSnapshot", read.catalogSnapshot(), "fact", read.result()),
          read);
    }
  }

  @Command(
      name = "review",
      description =
          "Retrieves the items awaiting review below a position, grouped per table, each with the"
              + " review material the Semantic Catalog built.")
  static final class ReviewCommand extends TranslatableRead {
    @picocli.CommandLine.ArgGroup(exclusive = false)
    private @Nullable PositionOptions position;

    @Option(
        names = "--kind",
        paramLabel = "KIND",
        description = "Only items of this kind: confirmation, retirement, or conflict.")
    private @Nullable String kind;

    @Option(names = "--element", paramLabel = "URN", description = "Only items on this element.")
    private @Nullable String element;

    @Option(
        names = "--fact-address",
        paramLabel = "URN",
        description = "Only items at this Fact Address.")
    private @Nullable String factAddress;

    ReviewCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var query =
          new Review.Query(
              PositionOptions.of(position),
              kind == null ? null : constant(ReviewPresentation.Kind.class, kind, "--kind"),
              element,
              factAddress);
      var read = service.review(service.point(snapshot()), query, language());
      return withTranslations(
          envelope(
              "semantic-review",
              "catalogSnapshot",
              read.catalogSnapshot(),
              "groups",
              read.result()),
          read);
    }
  }

  @Command(
      name = "languages",
      description = "Shows the languages the domain declares and its primary one.")
  static final class LanguagesCommand extends SemanticCall {
    LanguagesCommand(Map<String, String> environment) {
      super(environment);
    }

    @Override
    Map<String, Object> call(SemanticCatalogService service) {
      var languages = service.languages();
      return envelope(
          "semantic-languages", "declared", languages.declared(), "primary", languages.primary());
    }
  }

  private static Knowledge.View view(boolean unconfirmed) {
    return unconfirmed ? Knowledge.View.WITH_UNCONFIRMED : Knowledge.View.CONFIRMED;
  }
}

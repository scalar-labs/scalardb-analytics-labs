# Languages in the Semantic Catalog

One Semantic Catalog serves a team whose members read different languages, such as a team that
works mainly in Japanese with members who read only English. This document explains how language
runs through the Semantic Catalog: what a domain declares, which text is knowledge in each language
and which is written in the domain's one recording language, how translations are made and read,
and how models and people come to write in the recording language. Other documents state only what
their part needs and link here.

## The model in brief

A domain declares the languages its readers use and which of them is primary. Text then falls into
two kinds:

- **Names** of business terms are knowledge in each language. A term has its own name in each
  declared language, and each name is a Fact the deciding party confirms.
- **Descriptions**, every other text, are to be written in the domain's **recording language**,
  its primary language, which is fixed when the domain is created. **[Planned]** Readers of other
  declared languages read a **translation**, which is derived for display and is not knowledge.

Every description in the log is to be in the recording language, whoever writes it.

**[Planned]** Generators, the policy evaluation model, and the Semantic Catalog's own deterministic
writers write in the recording language, and an agent translates what a person writes into it before submitting. A person reads in
the language their principal prefers, and an agent session can switch it. A Query Agent reasons on
originals.

The split rests on what the Semantic Catalog guarantees: that meaning is right. A translation says
the same meaning in other words, so recording it as a Fact would make the deciding party judge
every meaning once per language. A definition such as whether sales include returns also turns on
its exact words, so the reviewed original stays the only wording a query is composed from. A name
is different: the English name a team uses for 売上 may be "net revenue" rather than a literal
translation, so which word a team uses in a language is itself knowledge about the domain.

Fixing one recording language per domain, rather than recording a language with each text, keeps
every original comparable with every other and gives every translation the same source. It also
leaves no language for a writer to label wrongly.

## Declared languages

Each domain declares its languages as BCP 47 language tags, such as `ja` and `en`, and names one of
them primary. The declaration lives in the workspace configuration (see
[`../application.md`](../application.md#workspace)) and decides:

- for which languages a business term's name is missing knowledge;
- **[Planned]** into which languages text is translated;
- **[Planned]** which languages a read can ask for;
- in which language every description is to be written: the primary one, the domain's
  recording language.

A domain chooses as primary the language most of its readers, and its semantics owner, read
directly, because they then read the recorded text as written and only the others read
translations. A domain whose semantics owner reads English sets English as primary. Any declared
language can be primary, and the primary language cannot change once the domain exists (see
[the recording language](#descriptions-one-recording-language)).
Declaring further languages, or no longer declaring one other than the primary, is always
possible.

A language is written as a BCP 47 language tag that is well-formed and in its canonical form, so one
language always has one spelling: `ja`, `en`, `zh-Hant`, `pt-BR`, and `ja-JP-u-ca-japanese` are
accepted. `EN` and `zh-hant` are rejected, and the error names the canonical forms `en` and
`zh-Hant` to use instead; `en_US` is rejected as malformed. A domain declares at least one language and none twice, and its primary
language is one of them; without a stated primary, the first declared language is primary. Only the
form of a tag is checked, not whether the IANA Language Subtag Registry lists the language, so a
well-formed tag such as `xx` is accepted.

## Names: one Fact per language

A **language-qualified predicate** holds one value per declared language. `written-form`, a business
term's name, is the only one. Language is part of such a Fact Address's identity, beside the element,
the predicate, and the applicability scope (see
[`record-model.md`](record-model.md#identity-and-urns)): the business term `ec-sales` has a
`written-form` Fact Address for `ja` holding "EC売上" and another for `en` holding "EC sales", with
URNs ending in `…/written-form;ja` and `…/written-form;en`.

Names of one term hold at the same time, and a Catalog Snapshot selects one Fact per Fact Address
(see [`catalog-snapshot.md`](catalog-snapshot.md)). Putting language in the Fact Address therefore
partitions selection exactly where values coexist, and every existing rule applies per language
unchanged:

- each name is selected, judged, and retired on its own, and an English name can be reviewed
  without touching the Japanese one;
- two names proposed in one language conflict, while names in different languages never do;
- requesting all predicates of a business term reports the written form as missing knowledge for
  each declared language that has none, so a missing English name is work for Generators like any
  other gap.

The applicability scope already separates values that hold at the same time for different
questions; language separates values that hold at the same time for different readers. Keeping
language as an attribute of the value instead would put names in different languages at one Fact
Address and force selection, conflicts, and review to run per language inside it.

Analytical semantics inference proposes a business term's names in the languages the domain's
documents use them in (see [`generators.md`](generators.md#meaning-inference)). The model is told
the declared languages and gives each name with the language it is written in; each name becomes a
candidate at the Fact Address of its language, and a name in a language the domain does not
declare is dropped and recorded as missing input. A name is never copied from a translation,
because the word a team uses is the knowledge being recorded. **[Planned]** A confirmed business
term that lacks a selected name in some declared language triggers the inference again for that
name, with the term's other names and the element it refers to as input; with nothing that states
one, the name is an `inference`, judged like any other candidate.

Finding elements by written form matches the names of every language, because a reader may use a
name from a language other than the one they read in (see
[`public-api.md`](public-api.md#finding-elements)). **[Planned]** A call can name one language to
match only its names, and each result says which language's name matched and carries the term's
names in the other languages.

A name is one per language; synonyms within a language, such as 売上 and 売上高, are not
represented.

## Descriptions: one recording language

Every other text, a description such as a Field's meaning, a Dataset's row inclusion, a
correspondence's business definition, an applicability scope's description, or a free-description
predicate's definition, and likewise an evaluation's reason and review material, an answer's
wording, and usage feedback, is to be written in the domain's recording language. The language
belongs to the domain, not to each text: no log record carries a language of its own, and whether a
reader reads the original or a translation follows from comparing the language read in with the
recording language.

The recording language is the primary language declared when the domain is created. The store keeps
it with the domain, and an operation on a domain whose declared primary language is now another one
fails and writes nothing, so a domain's log is never in two languages. A team that wants to record in
another language creates another domain.

Every writer writes in the recording language:

- **[Planned]** Generators and the policy evaluation model are instructed to write in the recording
  language. A quotation from a document stays in the document's own words, because it is checked
  against the document's text.
- **[Planned]** Text the Semantic Catalog composes without a model is written in the recording
  language too: the business definition schema interpretation states for a foreign key, the reason
  inline policy evaluation gives, and the reason of a new domain's default user policy version. How
  is not decided yet; these texts are written in English today, whatever the recording language.
- **[Planned]** A person may write in any language. The agent that submits a person's answer
  wording, conflict resolution wording, or usage feedback translates it into the recording language
  first, so the Semantic Catalog records only text in the recording language (see
  [Agents and sessions](#agents-and-sessions)).

The Semantic Catalog does not detect the language of text it is given, so text in another language
is recorded as it is; keeping to the recording language is the writer's part.

## Translations

**[Planned]** This section describes design that is not implemented yet.

A **translation** says an original's text in another declared language. A commit that records text,
in a Fact's value, an evaluation's reason or review material, an answer's wording, or usage
feedback, creates a translation build task (see
[`construction.md`](construction.md#build-tasks)). The task translates each such text from the
recording language into every other declared language, through the same language-model adapter as
Generators (see [`generators.md`](generators.md#reaching-a-language-model)), and keeps the result
outside the log, keyed by the record that holds the text and the target language, with the model
version that made it. Adding a declared language creates translation tasks for the text already recorded. A
failed translation task is resumed like any other.

A translation changes no Fact's state. It is never evaluated or reviewed, and never a dependency or
an input read of a candidate: Generators and the policy evaluation model read originals. Business
term names are not translated, because a name in another language is a Fact of its own.

Translating in a build task rather than on read keeps reads free of model calls and side effects. A
read therefore returns only translations that exist and reports the rest as pending.

A mistranslation cannot be caught by review. Every translation is therefore marked as one, and the
original is always returned with it.

## Reading in a language

**[Planned]** This section describes design that is not implemented yet.

A read takes, optionally, the language to read in, one of the domain's declared languages. Without
one, the application supplies the language the using party's principal prefers, and without a
preference the domain's primary language is used. Every text a read returns comes as its original,
in the recording language, and, when the language read in is another, with its translation or a
mark that the translation is pending. Reading the domain's languages returns the declared
languages and the primary one. The exact contract is in [`public-api.md`](public-api.md#common-contract).

Review material is written in the recording language like other generated text, so a deciding
party who reads another language reviews it as a translation, with the original beside it (see
[`construction.md`](construction.md#review)).

## Agents and sessions

**[Planned]** This section describes design that is not implemented yet.

The application keeps each principal's preferred language and fills it in on reads that name none.
An agent working with a person, in review or in answering a question, follows the language the
person asks for in whatever words: it maps the request to a declared language and passes it on
every later read of the session, and changes the principal's preference only when asked to change
it from then on. It shows the Semantic Catalog's translations and never translates knowledge
itself, so a reader can always tell a translation from the wording that was judged. Its own
explanations it writes in the reader's language. What the person writes for the Semantic Catalog to
record, such as a correction's wording or usage feedback, it translates into the recording language
before submitting it.

A Query Agent composes SQL from original text and requests translations only for text it shows the
user, such as a definition it cites, so an unreviewed translation never shapes a query. How the
Agent Skill carries this is in [`../application.md`](../application.md#query-agents).

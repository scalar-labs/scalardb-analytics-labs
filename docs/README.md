# Developer documentation

The documents in this directory help developers change ScalarDB Analytics without reconstructing
its architecture from source code. They describe established behavior and accepted design
direction. They are not a product user guide, implementation journal, roadmap, or issue tracker.

Start with [`architecture.md`](architecture.md). It introduces the system-wide mental model and
links to the architectural units needed for deeper understanding.

## Documentation layers

- Top-level `docs/*.md` files explain one coherent architectural unit: its purpose, ownership,
  relationships, constraints, and representative flows. When one file cannot hold a unit's mental
  model, `docs/<unit>.md` stays the unit's overview and entry point, and `docs/<unit>/*.md` files
  each explain one part of that unit in depth. Every part is reachable from the overview.
- `docs/design/*.md` files preserve the current answer and rationale for one explicit design
  question. These records are mutable and must be rewritten when the decision changes.
- Module READMEs provide only enough local orientation to use or change that module and link back
  to the canonical architectural explanation.
- The issue tracker owns proposals, implementation gaps, migration work, and delivery status.
- Source code and generated reference documentation own low-level APIs and exhaustive details.
- [`issue-workflow.md`](issue-workflow.md) is not an architectural unit. It describes how this
  repository uses Issues, labels, milestones, and the changelog.

## Placement rules

Put a claim in the top-level documentation when it is established, stable enough to maintain, and
needed to understand ownership, dependencies, invariants, or an important end-to-end flow. Put it
under `design/` only when it answers one question that could reasonably have had another answer and
its rationale will help future developers protect or reconsider the choice.

Mark accepted design direction that is not implemented yet with **[Planned]**. At the start of a
paragraph, list item, or table cell, the marker covers that whole unit; inside one, it covers only
the sentence it begins. Below a document's title, it covers the whole document. A change that
implements it removes the marker in the same change.

Each settled claim has one canonical home. Other documents may summarize it only as needed for
their own mental model and should link to that home. Rewrite or remove superseded material instead
of retaining before-and-after narration; Git preserves history.

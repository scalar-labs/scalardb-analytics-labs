# How should DuckDB be packaged for the single-binary product experience?

## Current answer

Provide one application process and one user-facing executable entry point, while allowing the
distribution to contain DuckDB shared libraries and the loadable product and connectors extensions. Ship
those artifacts as a verified, version-aligned set and disable runtime extension downloads.

## Rationale

DuckDB's loadable extension model is required to load the connectors' DuckDB extension, which ships
with the connectors distribution and reaches every source through its Connector, and to keep product
integration independently buildable. Statically linking a fixed extension set into one physical
executable would couple both to application linking.

The user-facing property needed by the product is a single application lifecycle, not a single
file. Packaging supporting native artifacts alongside the executable preserves that lifecycle
while retaining DuckDB's supported extension boundary.

## Consequences

- The application owns process startup and shutdown, the distribution owns artifact compatibility,
  and the Query Engine owns extension loading.
- DuckDB and every packaged extension must be version-aligned and tested as one distribution.
- Required artifacts must be available locally; the runtime must not install extensions from the
  network.
- Build orchestration must treat native libraries and extensions as declared distribution inputs.
- Deployment tooling may install multiple files even though users invoke one executable.

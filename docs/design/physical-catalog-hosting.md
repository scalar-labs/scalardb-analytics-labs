# Where does the Physical Catalog run, and how does DuckDB reach it?

## Current answer

The Physical Catalog runs inside the application process as Java code, and the application calls
it directly. DuckDB reaches it only through a provider table that the application registers into the
product extension after loading it. The extension depends only on that host ABI. It does not load
the catalog, create isolates, or know how the catalog is hosted.

## Rationale

The application, not only DuckDB, needs the catalog. Registering and administering data sources
must stay out of reach of untrusted SQL, and credentials must not travel in SQL text. Hosting the
catalog in the application lets those operations stay ordinary application calls, while DuckDB gets
a narrower, read-only surface.

The alternative was to build the catalog as a separate Native Image shared library that the
extension loads. That shape needs handoffs from the application to both the library and the
extension, two Native Images with separate isolates in one process, and a native build even for JVM
development. One image with one isolate keeps a single Java binding for the catalog, and later for
the connector library that the application initializes once and shares.

A probe confirmed the mechanism this depends on: FFM upcalls from native threads that Java did not
create work inside Native Image, as well as on the JVM.

## Consequences

- The extension's host table is versioned and sized, so later versions can add connector library
  handles without breaking existing hosts.
- The provider admits only read-only catalog methods and removes credentials from every response
  and failure.
- A DuckDB CLI artifact remains possible outside the product path, with the catalog built as a
  separate shared library acting as the provider. Both paths see the same catalog because its state
  lives in the ScalarDB store, not in process memory.

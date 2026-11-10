# Why do both catalogs receive table metadata from one retrieval?

## Current answer

The Physical Catalog and the Semantic Catalog share a single retrieval of table metadata. The
application asks the Physical Catalog to retrieve it through the connector, the Physical Catalog
versions the parts it needs, and the application passes the whole of the same metadata, exactly as
the connector returned it, to the Semantic Catalog. The Physical Catalog persists only what
planning, execution, and browsing need; the Semantic Catalog keeps the full source declarations,
including constraints.

## Rationale

Separate retrievals would let the Semantic Catalog see a new column or type before the Physical
Catalog has a version of it, and build knowledge on it. SQL composed from that knowledge would then
fail name resolution on a column the Physical Catalog does not have, or be planned with an old type.
With one retrieval, every schema the Semantic Catalog receives has a Physical Catalog version.

Passing the connector's metadata rather than what the Physical Catalog stored keeps a single copy of
each source declaration. If the Physical Catalog also stored constraints and source-side identifiers
that nothing on its side uses, the same declarations would exist in two places, and an error in the
unused copy would go unnoticed.

## Consequences

- The application passes every retrieved table, including unchanged ones, so a hand-off lost between
  the Physical Catalog's update and the Semantic Catalog is repaired by the next retrieval. A failed
  hand-off is retried by retrieving again, not by resending a stored copy.
- If the version the Semantic Catalog last received equals the Physical Catalog's current one, both
  see the same metadata.
- Users and Query Agents that need keys and constraints read them from the Semantic Catalog.
- Credentials stay in the Physical Catalog's registration and never travel with the metadata.

# Application module

This Java module is the product composition root and owns the user-facing application lifecycle.
It currently provides the machine-readable CLI. It will later assemble the Semantic Catalog,
Physical Catalog native library, DuckDB extension, and connector artifacts into the distributed
application.

The module does not own the domain logic of those components. The Query Agent is also outside the
module; the application will provide only its CLI-facing Agent Skill.

See [`../docs/architecture.md`](../docs/architecture.md) for component ownership and the
representative query flow.

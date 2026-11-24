# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
uses calendar versioning in the form `YYYY.M.D`, the release date without zero padding.

## [Unreleased]

## [2026.11.24] - 2026-11-24

### Added

- `scalardb-analytics` CLI with versioned JSON output for an external Query Agent: workspace
  initialization against a PostgreSQL store, PostgreSQL and MySQL source registration, table
  metadata discovery, and read-only SQL queries with time limits, cancellation, unsafe-SQL
  rejection, and secret redaction.
- Semantic Catalog that a build infers from source metadata, data checks, registered documents,
  and a language model, reviewed through a user policy and a review queue, and read in any declared
  language.
- Query Agent Skill and a Claude Code plugin that deliver the agent workflows.
- Distribution archives for `osx_arm64`, `linux_amd64`, and `linux_arm64` that need only a Java 25
  runtime and include scalardb-analytics-connectors 0.1.0.

[Unreleased]: https://github.com/scalar-labs/scalardb-analytics-labs/compare/v2026.11.24...HEAD
[2026.11.24]: https://github.com/scalar-labs/scalardb-analytics-labs/releases/tag/v2026.11.24

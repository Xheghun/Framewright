# Versioning policy

Framewright publishes its modules independently under one synchronized version. A release tag
`vX.Y.Z` publishes version `X.Y.Z` for every public artifact, even when only some modules changed.

Before `1.0.0`, minor releases may contain source or binary API changes. Such changes must be called
out in the changelog and migration notes. Patch releases are reserved for compatible fixes.

From `1.0.0`, the project will follow semantic versioning:

- major: incompatible public API, behavior, or persisted-schema changes;
- minor: backward-compatible features;
- patch: backward-compatible fixes and documentation.

The JSON `schemaVersion` is separate from the Maven artifact version. Readers reject unsupported
schema versions rather than silently interpreting data incorrectly.

Public Kotlin/Java ABI is tracked in each published module's `api` directory. CI fails when code and
the committed baseline differ. A maintainer should update an ABI baseline only after reviewing the
compatibility impact and documenting intentional changes.

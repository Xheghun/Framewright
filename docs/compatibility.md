# Compatibility

## v0.1 baseline

| Requirement | Supported baseline |
| --- | --- |
| Android | minSdk 24 |
| Java bytecode | JVM 11 |
| Kotlin consumer | Kotlin 2.0 or newer |
| Media3 | 1.10.1 |
| Compose | Compose BOM 2026.02.01 for the overlay |
| Build toolchain used by this repository | JDK 21, Kotlin 2.2.10, AGP 9.3.0 |

## Why the app does not need Kotlin 2.2.10

The Kotlin version used to build a library is not automatically a minimum version for every
consumer. What matters is the language/API surface and the metadata written into the artifact.

Framewright is built with the Kotlin 2.2.10 compiler but its published modules explicitly target
Kotlin language and API version 2.0. The generated class metadata is version 2.0, so a Kotlin 2.0.x
application can consume the artifacts. Published POMs also request Kotlin stdlib 2.0.21 instead of
forcing the repository's compiler version into the consumer. There is no reason to update an
application from Kotlin 2.0.21 solely to use Framewright v0.1.

An application may still need to align versions for unrelated build reasons, such as its Android
Gradle Plugin, Compose compiler, KSP, or another dependency.

## Media3 alignment

The adapter, bandwidth monitor, and DRM inspector compile against Media3 1.10.1 and expose Media3
types in their public APIs. Use the same Media3 version in the consuming application. Gradle's
dependency resolution may otherwise select a newer transitive version, but Framewright does not
promise binary compatibility with an arbitrary Media3 release.

## Compose alignment

Only `framewright-diagnostics-overlay` requires Compose. Its public API exposes Compose `Modifier`,
so the published metadata declares Compose UI transitively. The host still controls its Compose BOM
and theme. Validate upgrades in the host application before overriding the tested BOM.

## Android API behavior

The minimum supported API is 24. Codec and DRM capabilities vary by device, vendor, Android
version, and active output path. Missing capability fields represent unavailable or inapplicable
platform data, not necessarily an instrumentation failure.

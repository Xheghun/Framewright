# Privacy and security

Playback diagnostics can contain operational and device data. Treat exported sessions as user data
and apply the host application's retention, consent, access-control, and deletion policies.

## Default protections

- Media URIs are sanitized by default. User info, query parameters, and fragments are removed.
- Error messages are excluded by default because exception text may contain URLs or server detail.
- DRM license URLs, request/response bodies, credentials, session IDs, and raw key IDs are not
  exported. Identifiers needed for correlation are transformed into non-reversible safe values.
- Storage remains local to the application's Room database. Framewright performs no upload.

## Host-controlled sensitive options

Setting `includeErrorMessages = true` in Media3 or DRM configuration can expose data supplied by the
player, network stack, CDN, or license server. Enable it only for controlled diagnostics and review
exports before sharing.

A custom `DiagnosticUriSanitizer` replaces the safe default. Its output is persisted and exported
as-is:

```kotlin
val configuration = Media3DiagnosticsConfiguration(
    uriSanitizer = DiagnosticUriSanitizer { uri -> yourAllowlistedSanitizer(uri) },
)
```

Do not return authorization tokens, signed query strings, cookies, user identifiers, or full
private URLs.

## Recommended host policy

1. Tell users or testers when diagnostics are collected.
2. Keep retention short and expose deletion where appropriate.
3. Encrypt or protect exports in transit and at rest.
4. Restrict diagnostic access in production builds.
5. Scrub application-specific metadata before upload.
6. Never log exported JSON or DRM errors indiscriminately in production.

Framewright provides diagnostic data, not a compliance boundary. The integrating application owns
the final data classification and legal basis for collection.

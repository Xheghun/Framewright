# Release process

Framewright publishes seven artifacts to Maven Central from a signed Git tag. `app`, `media-lab`,
`docs-snippets`, and build logic are never published.

## One-time Maven Central setup

1. Verify the `io.github.xheghun` namespace in the Central Portal.
2. Create a Central Portal publishing token.
3. Create a password-protected OpenPGP signing key whose public identity can be verified.
4. Create a protected GitHub Actions environment named `maven-central`.
5. Add these environment secrets:
   - `MAVEN_CENTRAL_USERNAME`
   - `MAVEN_CENTRAL_PASSWORD`
   - `SIGNING_IN_MEMORY_KEY`
   - `SIGNING_IN_MEMORY_KEY_PASSWORD`

The username and password are the Central Portal token credentials, not the account password. Store
the ASCII-armored private signing key as the signing-key secret.

## Prepare a release

1. Update [the changelog](../CHANGELOG.md) and remove the `Unreleased` placeholder only when the
   release contents are final.
2. Review intended API changes and update ABI baselines with the module's API dump task.
3. Run the full local gate:

   ```bash
   ./gradlew spotlessCheck verifyReleaseReadiness
   ```

4. Inspect artifacts under `build/local-maven-repository` and generated Dokka output. To test an
   exact non-snapshot version locally, provide local signing credentials and add
   `-PVERSION_NAME=0.1.0`.
5. Merge the release-ready commit into `main`.

## Publish

Create and push an annotated tag from the intended commit on `main`:

```bash
git tag -a v0.1.0 -m "Framewright 0.1.0"
git push origin v0.1.0
```

The release workflow verifies that the tag is an exact `vMAJOR.MINOR.PATCH`, verifies that its
commit is contained in `origin/main`, reruns the local publication gate with the tag version,
publishes and releases the signed Central deployment, and creates a GitHub Release.

Never reuse or move a published tag. If publishing fails before Central accepts the deployment, fix
the workflow and rerun it. If Central has accepted the version, publish a new patch version instead.

## Verify after publishing

1. Confirm all seven coordinates and their source/Javadoc artifacts are visible in Central.
2. Resolve the release from a clean consumer build.
3. Update the external sample application to released coordinates and open its PR.
4. Smoke-test clear and Widevine playback on a physical device.
5. Confirm JSON export and storage migration behavior before announcing the release.

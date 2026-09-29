# Diagnostics overlay

Coordinate: `io.github.xheghun:framewright-diagnostics-overlay:$latestVersion`

The overlay is a stateless Compose surface plus a throttling reducer ViewModel. It never owns the
player or diagnostics session.

Forward events to the ViewModel:

```kotlin
val overlayViewModel: DiagnosticsOverlayViewModel = viewModel()

LaunchedEffect(diagnostics) {
    diagnostics.events.collect(overlayViewModel::onDiagnosticEvent)
}
```

Render its state inside the host theme:

```kotlin
val state by overlayViewModel.state.collectAsStateWithLifecycle()

DiagnosticsOverlayScreen(
    state = state,
    onAction = overlayViewModel::onAction,
)
```

Open it with `DiagnosticsOverlayAction.ToggleVisibility`; the built-in close action only hides the
surface. Non-critical high-frequency updates are coalesced, while session boundaries and fatal
errors publish immediately.

The artifact exposes Compose UI types but does not impose an application theme, navigation system,
window, or player layout.

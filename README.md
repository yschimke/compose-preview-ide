# Compose Preview IDE

IntelliJ Platform integration for Compose Preview and Compose UI Builder, extracted from
[compose-ui-builder](https://github.com/yschimke/compose-ui-builder) at
[`a1754a5`](https://github.com/yschimke/compose-ui-builder/commit/a1754a5d35520d481f23023d2a2959c019309766).
Source history before extraction is available in that repository under `ui-builder-intellij-plugin/`.

## Build and test

Install JDKs 17 and 21 (and 25 for the installed IDE smoke tests), then:

```bash
./scripts/checkout-ui-builder.sh
./gradlew check buildPlugin
./gradlew runIde
# Linux: the packaged ZIP installed in both supported IDEs
xvfb-run --auto-servernum ./gradlew installedPluginSmoke
```

Unit tests, plugin descriptor validation, schema packaging and the installable ZIP run in CI.
The installed-plugin smoke runs on pull requests, weekly and before every release for both IntelliJ IDEA and
Android Studio. It can also be dispatched manually.

## Source boundary

This repository owns the plugin sources, tests, descriptor, CI and releases. UI Builder owns the
editor and `:ui-builder-host-jvm`. They are compiled through a Gradle composite build at the exact
commit in `ui-builder-revision.txt`; no sibling checkout is assumed and no source is duplicated.
The version catalog comes from that same checkout so Kotlin and Compose match the editor.
Schemas are copied from the pinned export module's built JAR, not maintained here separately.

For coordinated development, use `-PcomposeUiBuilderDir=/path/to/compose-ui-builder`. Release CI
always runs the checkout script and uses the recorded commit. To upgrade, change the SHA, run the
checkout script and all tests, and include the pin update in a PR.

## Releases

Release Please opens a version/changelog PR on main. Merging it builds and smoke-tests the ZIP,
then uploads it to the new GitHub release. `workflow_dispatch` can retry an existing release tag.
No JetBrains Marketplace credentials are needed; distribution remains **Install Plugin from Disk**.
The first independent release is 3.87.0, continuing above the last bundled release (3.86.0) so
existing installations can upgrade. Later minor releases are independent of UI Builder versions.
The plugin ID `ee.schimke.composeai.ui-builder-poc` and ZIP name
`compose-ui-builder-intellij-plugin-<version>.zip` remain stable.

Actions must be allowed to create pull requests in repository settings. An optional
`RELEASE_PLEASE_TOKEN` is supported; with the default token the workflow explicitly dispatches
release-PR validation and invokes the reusable release workflow so GitHub's event suppression
does not skip either lane.

## Using the plugin

The plugin embeds that same offline editor in IntelliJ's main editor
area. It uses Jewel's Swing bridge for the Compose/Swing boundary and stores a separate workspace
for each IDE project and catalog under the IDE system directory. The **Compose UI Builder** tool
window is the separate preview view, with **Material 3** and **Wear M3** tabs. Its title actions open
the corresponding visual-editor tab; opening the tool window for the first time opens Material 3.
The editor and preview share one project session, so a saved edit is reconciled into both views.
Selecting a Material 3 or Wear M3 editor tab selects its matching Preview tab as well.

Run a sandbox IDE with:

```bash
./gradlew runIde
```

Open **View → Tool Windows → Compose UI Builder** in the sandbox, then use **Open Material 3
editor** or **Open Wear M3 editor** in the tool-window title bar. The visual canvas occupies an IDE
editor tab; Preview (and Native where a host supplies it) stays in the tool window. The local editor persists edits without a server. Remote designs use the host connection
described below; discovery of the open project's composables is not yet supported.

The plugin also recognizes published `DesignDocumentV1` files under the project's conventional
`ui-builder/designs/` directory. Open one from the Project view, or choose **Open checked-in
design** in the UI Builder tool-window title bar. Its **Design** editor tab and the separate Preview
edit the checked-in JSON directly; the ordinary JSON editor remains available beside it for review
and git diffs. A valid saved change from the JSON editor, an IDE agent or another process is adopted
automatically by the open visual editor and Preview. Invalid JSON leaves the last valid design on
screen with a status message, and an unsaved or concurrent JSON change is never overwritten.

Choose **Browse server designs** to connect to a compose-preview host. The plugin requests a
short-lived `ui-builder-read`, `ui-builder-write`, and `ui-builder-export` grant, opens its approval
page in the browser, lists the designs that actor may access, and opens the selected design as a
live editor tab. Server, browser, agent, editor, and Preview changes all use the same revisioned
protocol and update stream.

**Copy active design for an agent** copies a source-specific handoff. For a checked-in design it
names the repository JSON file, which an IDE agent can read and edit directly while the visual
editor automatically follows valid saves. For a remote design it names the design id and the server's
`/mcp` endpoint, where the agent requests its own grant rather than receiving the IDE's credential.
The plugin deliberately does not embed another MCP server: HTTP/WebSocket routes and MCP tools
belong to the compose-preview host, while the checked-in file is already the local agent boundary.

The plugin supplies Jewel chrome for the shared editor toolbar, panel rails, property-inspector
shells, draft and Theme fields, binding/actions, boolean and Add-property controls, Insert/Layers
navigator frame, headings, close action, search fields, component-browser controls and tile shells,
editor and export menus, workspace choices, and layer/canvas context menus. The web and standalone
desktop hosts continue to render those controls with Material 3. In every host the canvas and the
content inside component thumbnails remain the same catalog-backed Compose render: Jewel changes
the IDE controls around a design, not the design being authored.

Repository releases include `compose-ui-builder-intellij-plugin-<version>.zip`. Install it with
**Settings → Plugins → Install Plugin from Disk**, then restart the IDE and open the tool window.
The plugin requires IntelliJ Platform build 262 (IntelliJ IDEA 2026.2.x); older IDEs filter
the ZIP out of the plugin chooser as incompatible.

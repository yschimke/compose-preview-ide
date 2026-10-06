pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

rootProject.name = "compose-preview-ide"

// The frontend host is a pinned source dependency, not a published Maven library. An explicit
// local override supports coordinated edits; normal CI and releases use the recorded commit.
val uiBuilderDirectory =
  file(providers.gradleProperty("composeUiBuilderDir").orElse(".upstream/compose-ui-builder").get())

require(uiBuilderDirectory.resolve("settings.gradle.kts").isFile) {
  "Run ./scripts/checkout-ui-builder.sh first, or set -PcomposeUiBuilderDir=<checkout>."
}

// Compiler, Compose, and IntelliJ build-tool versions come from the same pin as the editor.
dependencyResolutionManagement {
  versionCatalogs {
    create("libs") { from(files(uiBuilderDirectory.resolve("gradle/libs.versions.toml"))) }
  }
}

includeBuild(uiBuilderDirectory) {
  dependencySubstitution {
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-host-jvm"))
      .using(project(":ui-builder-host-jvm"))
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-export"))
      .using(project(":ui-builder-export"))
  }
}

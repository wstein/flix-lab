plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "dev.wstein.flixplugin"
version = "0.1.0"

// Repositories are declared once, centrally, in settings.gradle.kts's dependencyResolutionManagement
// block -- a repositories{} block here would take precedence over it (Gradle's default
// PREFER_PROJECT mode) and silently drop the intellijPlatform.defaultRepositories() entry that
// resolves LSP4IJ from the JetBrains Marketplace.

dependencies {
    intellijPlatform {
        // Pin to a build within LSP4IJ 0.20.1's declared compatibility range (IDEA_COMMUNITY
        // 2024.2+); bump freely, this isn't load-bearing on any specific patch.
        intellijIdea("2025.2.6.2")

        // LSP4IJ provides the generic LSP + DAP client machinery this plugin builds on -- see
        // jetbrains-plugin/README.md for why (native platform LSP support has no DAP
        // equivalent yet, LSP4IJ's DAP client is the only generic path into IntelliJ today).
        plugin("com.redhat.devtools.lsp4ij", "0.20.1")

        // Bundled with the platform itself (not a Marketplace plugin) -- needed on the compile
        // classpath for FlixTextMateBundleProvider, which registers the bundled fallback grammar
        // via the real com.intellij.textmate.bundleProvider extension point. LSP4IJ itself depends
        // on this the same way (see its plugin-textmate.xml) for the identical extension point.
        bundledPlugin("org.jetbrains.plugins.textmate")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Embeds the project's existing debug-adapter/src/FlixDebugAdapter.java into this plugin's
// resources, so the plugin is self-contained (no dependency on the *.flix project being open
// also containing debug-adapter/) -- mirrors LSP4IJ's own "Embed the DAP server" pattern
// (docs/dap/DeveloperGuide.md). FlixDebugAdapter.java stays the single source of truth; this
// only copies it, it isn't forked.
val copyDapServer = tasks.register<Copy>("copyDapServer") {
    from("../debug-adapter/src/FlixDebugAdapter.java")
    into(layout.buildDirectory.dir("generated-resources/dap"))
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated-resources"))
        }
    }
}

tasks.processResources {
    dependsOn(copyDapServer)
}

intellijPlatform {
    pluginConfiguration {
        name = "Flix"
        ideaVersion {
            // LSP4IJ itself supports 2024.2+ (build 242+), but this plugin has only actually been
            // run against 2025.2.6.2 (build 252, the platform pinned above) -- since-build matches
            // that rather than claiming untested compatibility down to 242, which is also what
            // verifyPluginProjectConfiguration flags as inconsistent when the two diverge.
            sinceBuild = "252"
        }
    }
}

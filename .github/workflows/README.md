# GitHub Workflows

This repository contains several GitHub workflows that automate building, testing, and deploying the ajLeaderboards plugin.

## Workflows

### `gradle.yml` - Main Build & Artifact Upload
**Triggers:** Push to any branch, Pull requests
**Purpose:** Builds the plugin and uploads it as a downloadable artifact

**What it does:**
1. Sets up JDK 17 environment
2. Builds the plugin using Gradle's `shadowJar` task
3. Uploads the plugin JAR as `plugin-jar` artifact
4. Uploads all build files as `build-files` artifact

**Artifacts:**
- `plugin-jar` - The main plugin JAR file (ajLeaderboards-*.jar)
- `build-files` - All build outputs (retained for 2 days)

### `release.yml` - Production Release
**Triggers:** Push to `master` branch
**Purpose:** Handles production releases, including updater integration and API publishing

### `prerelease.yml` - Development Release  
**Triggers:** Push to `dev` branch
**Purpose:** Handles pre-release builds with external deployment to Polymart/Modrinth

### `javadocs.yml` - Documentation Publishing
**Triggers:** Push to `master` branch  
**Purpose:** Builds and publishes JavaDocs to GitHub Pages

## Downloading Artifacts

After any build completes, you can download the plugin JAR:
1. Go to the Actions tab in GitHub
2. Click on the workflow run
3. Download the `plugin-jar` artifact
4. Extract the ZIP to get the plugin JAR file
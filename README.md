# PluginBatchManager

Paper/Purpur and Velocity plugin batch updater. Preserves downloaded JAR filenames; moves old JARs to `plugins/.disabled/<batch>/` rather than deleting them.

## Download built JARs with GitHub Actions

1. Open **Actions** → **Build PluginBatchManager**.
2. Open a successful run or select **Run workflow**.
3. Under **Artifacts**, download **PluginBatchManager-Paper** or **PluginBatchManager-Velocity**.
4. Unzip and place the JAR into the respective `plugins/` folder. Restart the server/proxy.

## Build locally

Requires Java 25 and Gradle 9.2.1+. Run `gradle :paper:jar :velocity:jar`.

See [日本語の説明](README_ja.md) for configuration, commands and limitations.

**Status:** Experimental; live server operation and real source downloads have not yet been verified. Use a test server first.

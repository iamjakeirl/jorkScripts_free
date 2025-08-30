# Repository Guidelines

## Project Structure & Modules
- `utils/`: Shared Java utilities (`com.jork.utils`), packaged as a library.
- `scripts/jorkHunter/`: Main script module (`com.jork.script.jorkHunter`), resources under `src/resources` and `src/main/resources`.
- `lib/`: Local dependencies (e.g., `API.jar`, used as `compileOnly`).
- `example/`, `reference/`: Examples and docs/assets for development.
- Gradle settings: multi-module build with Java 17 toolchain.

## Build & Development
- `./gradlew tasks`: Lists available tasks and variants.
- `./gradlew build`: Compiles all modules and runs checks.
- `./gradlew :scripts:jorkHunter:jar`: Builds the default all-features JAR to `scripts/jorkHunter/jar/` and copies to `~/.osmb/Scripts`.
- `./gradlew :scripts:jorkHunter:jarBirdSnares`: Builds Bird Snares–only variant.
- `./gradlew :scripts:jorkHunter:jarChinchompas`: Builds Chinchompas–only variant.
- `./gradlew :scripts:jorkHunter:buildAllVariants`: Builds all variants.
Notes: Use the provided Gradle wrapper. Add `--refresh-dependencies` if resolving jars after updates.

## Coding Style & Naming
- Language: Java 17. Indentation: 4 spaces; UTF-8; LF line endings.
- Packages: `com.jork.*`. Classes: UpperCamelCase; methods/fields: lowerCamelCase; constants: UPPER_SNAKE_CASE.
- Resources: put config in `scripts/jorkHunter/src/resources/` and images in `scripts/jorkHunter/src/main/resources/`.
- Dependencies: reference local jars from `lib/` via Gradle (do not hardcode absolute paths).

## Manual Testing & Usage
- Build a variant (see commands above); JARs land in `scripts/jorkHunter/jar/` and auto-copy to `~/.osmb/Scripts`.
- Launch your custom client and load the corresponding script.
- Validate key flows: trap placement patterns, state transitions, metrics overlay, and interaction handlers.
- Adjust config via files in `scripts/jorkHunter/src/resources/` (e.g., `*-config.properties`) and rebuild.

## Commit & Pull Request Guidelines
- Commits: short, imperative subject (e.g., "add metrics panel"), optional scope (`utils:`, `jorkHunter:`). Group related changes only.
- If using PRs: include a brief description, before/after notes or screenshots (e.g., UI overlays), and any config changes.
- Build must succeed; avoid committing generated artifacts except preconfigured JAR outputs under `scripts/jorkHunter/jar/`.


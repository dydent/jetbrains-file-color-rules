# File Color Rules

File Color Rules is an open-source plugin for JetBrains IDEs that colors files and folders in the Project View using project-local YAML rules.

The plugin is under active development. The repository currently produces installable ZIP builds; the first JetBrains Marketplace beta will follow after the initial vendor review.

## Features

- Ordered, first-match-wins rules.
- Project-relative path, name, extension, and file/folder conditions.
- Nested `all`, `any`, and `not` logic.
- Portable glob matching and bounded-time RE2/J regular expressions.
- Theme-aware light and dark palettes.
- YAML hot reload with last-valid fallback.
- Project View context actions.
- Visual rule and palette management plus a raw YAML editor.
- No telemetry and no runtime network requests.

## Installation

### Development build

1. Run `./gradlew buildPlugin`.
2. In a supported JetBrains IDE, open **Settings → Plugins**.
3. Use the gear menu and select **Install Plugin from Disk…**.
4. Choose the ZIP from `build/distributions`.

Marketplace installation will be documented when the beta is approved.

## Configuration

Create `.jetbrains-file-colors.yaml` in the project root:

~~~yaml
version: 1

options:
  enabled: true
  caseSensitivity: auto

colors:
  generated: "#E7F7E7"
  tests:
    light: "#DCEBFF"
    dark: "#203A5A"

rules:
  - id: tests
    name: Tests
    description: Unit and integration test sources
    enabled: true
    color: tests
    when:
      any:
        - pathGlob: "**/test/**"
        - pathGlob: "**/tests/**"
~~~

Rules are evaluated in order. The first enabled matching rule supplies the color.

### Conditions

| Condition | Meaning |
| --- | --- |
| `pathEquals` | Exact normalized project-relative path |
| `underPath` | Directory itself and all descendants |
| `pathGlob` | Whole-path glob |
| `pathRegex` | Whole-path RE2/J expression |
| `nameGlob` | Glob matched against the basename |
| `extension` | Extension without a leading dot |
| `kind` | `file`, `folder`, or `any` |
| `all`, `any`, `not` | Nested boolean logic |

Paths always use `/`; `.` represents the project root. Absolute paths and `..` traversal are rejected. Globs support `*`, `?`, and `**`.

Configuration is limited to 1 MiB, 1,000 rules, 32 condition levels, and 10,000 condition nodes. Visual or context-menu changes serialize canonical YAML, so comments may not be preserved; use `name` and `description` for durable documentation.

## Supported IDEs

The plugin targets IntelliJ Platform build 261 and newer desktop IDEs. CI verifies the platform APIs against current 261 and 262 releases. JetBrains Gateway, JetBrains Client, Remote Development, and deprecated products are outside the initial scope.

## Development

Requirements:

- JDK 21
- Git
- Network access for the first Gradle dependency download

~~~shell
./gradlew test
./gradlew verifyPluginStructure
./gradlew buildPlugin
./gradlew runIde
~~~

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution expectations and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

Apache License 2.0. See [LICENSE](LICENSE).

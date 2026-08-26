# Contributing

Thank you for improving File Color Rules.

1. Open an issue before substantial behavior or schema changes.
2. Create a focused branch and add tests for behavior changes.
3. Run `./gradlew test verifyPluginStructure buildPlugin`.
4. Open a pull request using the repository template.

Keep rendering callbacks free of file content, disk, network, PSI, VCS, and index access. Parsing and matcher compilation must stay off the UI thread. New IntelliJ APIs must be public, non-obsolete, and available since build 261.

Contributions are accepted under Apache-2.0. No separate CLA or DCO is required.

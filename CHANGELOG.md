# Changelog

All notable changes to PhotX will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Repository bootstrap (`v0.1.0-bootstrap` foundation) with `:app`, `:core:common`, `:core:model`, and `build-logic:convention`.
- Product specification (`SPEC.md`) for PhotX with `#photx` versioned metadata contract.
- Gradle `9.6.1` wrapper, Version Catalog (`gradle/libs.versions.toml`), and convention plugins (`photx.android.application`, `photx.jvm.library`, `photx.quality`).
- Automated quality gates: Spotless `8.12.0` (`ktlint` `1.8.0`), Detekt `2.0.0-alpha.6`, Android Lint, and GitHub Actions CI workflow.
- Foundational documentation (`README.md`, `CONTRIBUTING.md`, `docs/architecture.md`, `docs/DECISIONS.md` with Proposed `ADR-001` & `ADR-002`, `docs/GLOSSARY.md`, `docs/INTERVIEW_PREP.md`, `docs/PROGRESS.md`, and initial session log).

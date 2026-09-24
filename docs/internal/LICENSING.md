<!--
  Copyright 2026 ReconcileFlow
  Author: Kunal Gandhre
  SPDX-License-Identifier: Apache-2.0
  Licensed under the Apache License, Version 2.0; see LICENSE.
  https://www.apache.org/licenses/LICENSE-2.0
-->

# Source documentation and licensing conventions

Use ReconcileFlow as the copyright holder and Kunal Gandhre as the author for this project's authored files. The project license is Apache-2.0; `LICENSE` contains the canonical license text and `NOTICE` contains project attribution. Do not modify the standard license text to insert a project banner, and preserve any third-party notices when incorporating third-party code.

## Header formats

| File type | Attribution format |
| --- | --- |
| Java, Go, JavaScript, CSS | Block comment, copyright, author and Apache-2.0 SPDX identifier |
| Maven POM, HTML, SVG, Markdown | XML/HTML comment; HTML keeps its doctype first |
| YAML, Git ignore patterns, Spring auto-configuration imports | Hash-prefixed comment lines |
| Go module manifest | Go-style line comments |
| JSON package metadata | Standard `license` and `author` fields, plus `copyright`; no JSON comments |
| Generated Go checksums | Adjacent `go.sum.license`; never insert comments into `go.sum` |

`website/package.json.license` also provides plain SPDX attribution for tools that do not read npm metadata. Companion license files apply to the repository's metadata file, not to the third-party dependencies it names. Dependencies retain their own licenses.

Build outputs inherit source attribution when copied; do not edit `target`, `dist`, tool caches or generated editor files. Untracked IDE configuration such as `.project`, `.classpath`, `.settings` and `.vscode` is machine-specific and is not part of this source attribution update.

## Comments that help readers

- Document each class/type's responsibility and each public operation's contract.
- Explain the state transitions, ownership checks, retry behavior and failure boundaries where they happen.
- Describe what tests prove, especially distinctions between a busy claim, a completed duplicate and an uncertain business outcome.
- In build and deployment files, explain module relationships, service assumptions and publishing boundaries.
- Keep website implementation notes in source comments; they must not appear as extra user-facing content.
- Update comments with behavior changes. Avoid narrating obvious syntax or promising exactly-once external effects.

## Checking a documentation-only change

1. Compare source tokens with the previous revision after removing comments; runtime logic should be identical.
2. Parse POM/XML, YAML and JSON in their native formats; compare parsed configuration while ignoring only intentional attribution metadata.
3. Build the Java modules and static site, and run the existing Go checks. Do not add tests that merely assert comment wording.
4. Inspect the staged diff so unrelated local edits and generated files are not included.

## Verification record — 2026-09-24

For this attribution and comment update, `mvn -B -ntp verify` with `REDIS_INTEGRATION=true` passed all 16 Java tests without skips. With the same environment flag, `go vet ./...` and `go test ./...` passed, including real Redis tests. `node --check` passed for all three website scripts and `node website/build.mjs` built the site. All four POMs and the SVG parsed as XML. A comment-aware comparison against the previous commit confirmed unchanged source tokens and configuration values, except intentional package attribution metadata; canonical LICENSE and Go checksums stayed unchanged. Browser and race tests were not repeated for this comment-only update; their earlier results are recorded in DEVELOPMENT.md.

---
title: Agent Skill
description: A portable Agent Skill for Claude Code, Codex, and other AI coding harnesses to work on the Klag repository with the right context and conventions.
---

The Klag repo ships a portable **Agent Skill** that packages repo-specific guidance for
AI coding agents. It helps agents find the right files, choose verification commands,
preserve project conventions, and leave changes ready for review.

## Files

- `skills/klag/SKILL.md` — portable skill entry point.
- `skills/klag/references/project-map.md` — detailed project map and change checklists.
- `skills/klag/agents/openai.yaml` — optional Codex/OpenAI UI metadata.

## Install

Use a **symlink** so edits in the repo are picked up immediately. If the destination
already exists as a real directory, move it aside first.

```bash
# Codex
mkdir -p "${CODEX_HOME:-$HOME/.codex}/skills"
ln -sfn "$(pwd)/skills/klag" "${CODEX_HOME:-$HOME/.codex}/skills/klag"

# Codex installations that use ~/.agents
mkdir -p "$HOME/.agents/skills"
ln -sfn "$(pwd)/skills/klag" "$HOME/.agents/skills/klag"

# Claude Code
mkdir -p "$HOME/.claude/skills"
ln -sfn "$(pwd)/skills/klag" "$HOME/.claude/skills/klag"
```

Or use a **copy** for a stable local snapshot:

```bash
cp -R skills/klag "${CODEX_HOME:-$HOME/.codex}/skills/klag"
cp -R skills/klag "$HOME/.agents/skills/klag"
cp -R skills/klag "$HOME/.claude/skills/klag"
```

For any other harness, copy or symlink the `skills/klag` directory into that tool's
Agent Skills directory. The contract is simply that the harness can discover `SKILL.md`
with YAML frontmatter fields `name` and `description`.

## Use

Invoke explicitly when starting Klag work:

```text
Use $klag to add a metric for ...
Use $klag to review the MCP implementation for ...
Use $klag to update the Helm chart for ...
```

Some tools invoke the skill automatically when a request mentions Klag, Kafka lag
exporter work, MCP tools, metrics, Helm chart changes, Docker/native builds, or
repository-specific tests.

## Review policy

The skill tells agents **not** to commit, push, release, deploy, or publish anything
unless explicitly asked, and to report changed files and verification results so you can
review all code and docs before integration.

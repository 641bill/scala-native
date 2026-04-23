# Agent Instructions For Rift

This repository is the active Rift worktree for the Scala Native fork.

Read these first:

1. `DESIGN.md`
2. `ROADMAP.md`
3. `docs/HANDOFF.md`
4. `/Users/siyaoliu/rift/Claude_output/CODEX.md`
5. `/Users/siyaoliu/rift/Claude_output/DESIGN.md`
6. `/Users/siyaoliu/rift/Claude_output/ROADMAP.md`

Operating rules:

- Work in `/Users/siyaoliu/rift/scala-native-rift` on branch `feature/rift` unless the user explicitly redirects you.
- Do not restart from the old standalone design. The active artifact is the Scala Native fork.
- Treat root `DESIGN.md` and `ROADMAP.md` as the active design/roadmap for this worktree.
- Treat benchmark claims as provisional unless they are backed by checked-in commands and recorded run counts.
- Do not edit `/Users/siyaoliu/rift/Claude_output/DESIGN.md` or `/Users/siyaoliu/rift/Claude_output/ROADMAP.md` casually.
- Preserve the distinction between runtime-only effects, topology/layout effects, and application-level DEBS effects.
- The worktree contains substantial uncommitted and untracked work. Do not revert or overwrite files unless the user explicitly asks.

---
name: jarvis
description: BusLink session wake-up. Use when the user says "jarvis", "jarvis wake up", "jarvis let's do it", "wake up", or otherwise asks to start/resume a BusLink session. Runs the docs/SESSION_START.md startup checklist and reconstructs project context.
---

# Jarvis — BusLink session startup

Start a BusLink working session. `docs/SESSION_START.md` is the source of
truth for this flow; if it has changed, follow the file, not this summary.

## Steps

1. Read `CLAUDE.md` (usually already in context; don't re-read if so) and
   `docs/SESSION_START.md`.
2. Run the SESSION_START **Startup Checklist** in order:
   - `docs/PROJECT_CONTEXT.md`
   - `docs/DEVELOPMENT_LOG.md` — the latest sections are enough (it is long)
   - `docs/ARCHITECTURE.md`
   - `docs/DEVELOPMENT_ROADMAP.md`
   - the active sprint file named in the roadmap
   - `docs/API.md` only if relevant
3. Check `git status` and the current branch, and whether `main` / `dev`
   are in sync with their remotes.
4. Reconstruct context, as SESSION_START says:
   - current project state (outcome first, short)
   - completed work in the active sprint
   - remaining tasks, or which sprint-close steps are still open
   - recommend **only the next logical task**
5. Mention any doc that contradicts the code, briefly.
6. Then stop and wait. Follow the **Question-Led Development** style from
   `CLAUDE.md` for everything after this: explain simply, one question at a
   time, the user decides, Claude writes the syntax.

Keep the summary short and readable. Don't start implementing anything.

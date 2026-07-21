# BusLink Session Startup

## Startup Checklist

At the beginning of every new session, read these files in order:

1. CLAUDE.md
2. docs/PROJECT_CONTEXT.md
3. docs/DEVELOPMENT_LOG.md
4. docs/ARCHITECTURE.md
5. docs/DEVELOPMENT_ROADMAP.md
6. Active Sprint File (referenced in DEVELOPMENT_ROADMAP.md)
7. docs/API.md (only if relevant)

---

## Reconstruct Context

After reading:

- Summarize the current project state.
- Review completed work.
- Identify remaining tasks in the active sprint.
- Recommend only the next logical task.

---

## Before Implementation

Before writing any code:

- Explain why the task is needed.
- Explain where it fits in the overall architecture.
- Explain the chosen approach and mention reasonable alternatives.
- Explain which files will be created or modified and their responsibilities.
- Explain the expected outcome.
- Wait for my approval before implementation.

---

## Development Principles

- Teach first, implement second.
- Assume I am learning while building the project.
- Explain concepts clearly before coding.
- Follow production-grade engineering practices.
- Write clean, modular and maintainable code.
- Do not modify unrelated code.
- Keep implementations aligned with the active sprint.
- Follow the active sprint strictly.
- Do not introduce additional tasks or reorder planned sprint tasks unless I explicitly approve.
- Suggestions are welcome, but treat them as optional recommendations and wait for my approval before changing the planned implementation order.

---

## Task Completion

After completing each task:

- Verify that the implementation satisfies the task objective.
- Recommend the next unfinished task from the active sprint.
- Remind me to update documentation if required.

---

## Sprint Completion

When all tasks in the active sprint are complete:

1. Announce sprint completion.
2. Summarize what was accomplished.
3. Merge `dev` into `main` and push `main`, so `main` and `dev` stay in sync at
   every sprint boundary (confirm with me before pushing `main`, same as any
   shared-state push).
4. Ask me to finalize the sprint in Notion.
5. Ask me to prepare the next sprint plan.
6. Generate the next sprint file after I approve the plan.
7. Update DEVELOPMENT_ROADMAP.md with the new active sprint.
8. Wait for my approval before beginning the next sprint.


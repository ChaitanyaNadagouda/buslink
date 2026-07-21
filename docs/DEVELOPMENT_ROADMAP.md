# BusLink Development Roadmap

## Current Phase
Backend Foundation

## Active Sprint
Sprint 2

## Sprint Status
✅ Complete — awaiting Sprint 3 plan

## Active Sprint File
docs/sprints/Sprint-02.md

---

## Planning Sources (Notion)

### Master Roadmap
Smart Offline-First Digital Ticketing System for Public Transport

Purpose:
Overall project vision and long-term roadmap.

### Sprint Tracker
BusLink — Sprint Task Tracker

Purpose:
Track sprint progress and completed milestones.

### Sprint Planning
Sprint plans are initially created and maintained in Notion.

Once a sprint plan is finalized, create a corresponding file under:

docs/sprints/

Example:

docs/sprints/Sprint-01.md

The sprint file becomes the implementation source of truth for Claude during development.

---

## Claude Instructions

- Treat the active sprint file as the implementation checklist.
- Focus only on the active sprint.
- Never implement future sprint work unless I explicitly activate it.
- After each completed task, recommend the next unfinished task from the active sprint.
- When the active sprint is completed:
  1. Notify me that the sprint has finished successfully.
  2. Summarize the completed work.
  3. Update every essentials doc that Sprint's work touched — don't assume only
     `PROJECT_CONTEXT.md`/`DEVELOPMENT_LOG.md` need it. Explicitly check each one
     against what the sprint actually built, not just the ones that were top of
     mind:
     - `docs/PROJECT_CONTEXT.md` — current state
     - `docs/DEVELOPMENT_LOG.md` — closure entry
     - `docs/ARCHITECTURE.md` — only if a design/tech decision changed
     - `docs/API.md` — any endpoint the sprint added or changed; a sprint that
       ships an endpoint isn't done until it's documented here
     - `docs/INTERVIEW_PREP.md` — a new `## Sprint N` section for any non-obvious
       decision made (bugs found + root cause, deviations from the plan,
       trade-offs discussed) — these are exactly the things worth being able to
       explain later and easiest to forget once the sprint is behind us
     - This `Sprint Status` line — flip to reflect closure
  4. Merge `dev` into `main` and push `main`, so `main` and `dev` stay in sync at
     every sprint boundary (confirm with me before pushing `main`, same as any
     shared-state push).
  5. Ask me to finalize the sprint in Notion.
  6. Ask me to prepare the next sprint plan in Notion.
  7. After I approve the next sprint plan, generate the next sprint file (for example, docs/sprints/Sprint-02.md).
  8. Update this roadmap with the new active sprint.
  9. Wait for my approval before beginning the next sprint.

This document should only change when the active sprint changes.

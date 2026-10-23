# AGENTS.md — Phone Companion

This file governs all agents (human or AI-assisted) working in this repo.
It applies on top of any global agent instructions.

## 1. Git authorship — hard rule

- NEVER overwrite the git author. Leave the configured default (`user.name` /
  `user.email`) alone.
- NEVER run `git config user.name`, `git config user.email`,
  `git commit --author`, `git commit --amend --author`,
  `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`, or any equivalent.
- If no git author is configured, STOP and ask the user to set it.
  Do not invent one. Do not fall back to a tool/model name.
- The AI model, harness, plugin, or tool is NEVER the author of a commit,
  even if it wrote all the code or ran the `git commit` command.
  Models and harnesses are automation tools used to help, not authors.
- You MAY record tooling info for transparency, but only in places that
  do not affect authorship:
  - commit message body trailers such as `Assisted-with: <model> via <harness>`
  - PR/issue comments
  - Example: `Assisted-with: Muse Spark via OpenCode` in the commit body.
- You MUST NOT use `Co-authored-by:` (or similar authorship-sharing
  trailers) for an AI model, bot, or tool. `Co-authored-by` is reserved
  for real human co-authors only.
- The commit author must always be the human user / repo owner.

## 2. Commits and PRs

- Keep commits small, conventional (`feat:`, `fix:`, `chore:`, `docs:` …),
  with a body explaining why when non-trivial.
- Do not commit secrets (`.env`, `*.token`, `secrets/`, private keys,
  device tokens). Do not print secrets in logs, PR comments, or chat.
- Do not commit plans, roadmaps, or orchestration notes. Those live in
  chat / the orchestrator thread, never in the repo.
- Verify before committing: build, lint, or test the touched piece
  where a fast check exists.

## 3. Project conventions

- Boring, maintainable stack. Kotlin for Android. Backend: small footprint,
  long-lived, Docker-first (`backend/` builds and runs from its Dockerfile).
- Repo layout: `android/` app, `backend/` (Dockerfile + compose example),
  `docs/`, per-component READMEs.
- Config-driven, no hardcoded hosts/users/credentials. Documented
  zero-to-paired setup in README.
- Secure by default: login-gated WebUI, per-device tokens with individual
  revocation, least privilege, TLS minimum, per-device payload encryption
  for secret-bearing content (see backend threat-model doc).
- Battery-conscious Android: shared generic polling plumbing, per-widget
  implementations (no single fully-generic renderer), manual refresh on open
  + roughly hourly background, screen-on aware, no exact alarms.

## 4. Reviews

- All implementation PRs get a review before merge.
- Review agents use the `thermo-nuclear-review` (security/correctness) and
  `thermo-nuclear-code-quality-review` (maintainability) skills.
- Fix review blockers in the PR branch; re-review before merging.

## 5. Conflicts and dependencies

- Prefer parallel, low-conflict PRs, but do not invent dependencies just
  to avoid a possible conflict. Fix conflicts at merge time when cheaper.

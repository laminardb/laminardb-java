# Plan 06 — Code review gates: structure, slop, documentation, dead code, test discipline

Status: **Accepted standard, not yet enforced** · Date: 2026-08-29
Applies to: **every PR and every phase exit** in this repository. Read alongside plan 00
§5 (cross-cutting conventions). The reviewer prompt `agents/code-review.md` operationalizes
this plan; CI automates the parts that can be automated.

## 1 — Purpose

Five failure modes this gate exists to catch, all of them common in agent-assisted and
fast-moving codebases:

1. **Non-idiomatic Java structure** — layer dumping, `Util` classes, wrong visibility,
   speculative abstraction.
2. **AI slop** — placeholder stubs, defensive noise, copy-paste artifacts, redundant
   indirection that reads like generated filler.
3. **Excessive in-code documentation** — comments narrating the obvious, essays on
   private members, Javadoc that restates the method name.
4. **Unwired/unused code paths** — public API nothing calls, native methods nothing
   invokes, options nothing sets.
5. **Excessive or weak tests** — permutation bloat, tests of mocks and private state,
   duplicated core guarantees, coverage-quota chasing.

A PR that trips any of these is REQUEST CHANGES, regardless of tests being green.

## 2 — Tooling enforcement (CI, grows with the phases)

| Gate | Tool | Scope | Wired at |
|---|---|---|---|
| Rust formatting + lints | `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` | all Rust glue | Phase 0 (plan 01) |
| Unused Rust deps | `cargo machete` | deps drift | Phase 0 |
| Unjustified allows | grep gate: `#[allow(` in `src/` requires an inline written justification (phase that needs it + why) | slop guard | Phase 0 |
| Java formatting | Spotless + palantir-java-format | all Java | Phase 0 |
| Java style | Checkstyle (curated ruleset committed at `.checkstyle.xml`: naming, imports, unused imports, empty catch, one-top-level-class) | all Java | Phase 0 |
| Bytecode analysis | SpotBugs (Maven plugin, `-medium` threshold, fail on bug) | all Java | Phase 1 |
| Unwired-code detector | JaCoCo rule: **fail the build if any production class has 0% instruction coverage**; coverage *percentages* are informational, never quotas | all Java | Phase 1 |
| Optional, stronger typing | Error Prone (replace or augment SpotBugs if it proves noisy) | all Java | Phase 2, on demand |

One command runs the full set: `just review` (see plan 01 Task 0.4). `just verify`
remains the correctness gate; `just review` is the review gate. CI runs both on every PR.

## 3 — Java structure standards

- **Packages by concept**, not by layer: `io.laminardb` (public API), 
  `io.laminardb.internal` (native seam — never referenced by user code). No package
  grows a `util`/`helper`/`common`/`misc` class (main-repo rule carried over); a static
  method lives on the type that owns the concept it operates on.
- **Terminology matches the core's domain vocabulary exactly**: source, stream, sink,
  watermark, epoch, barrier, checkpoint, vnode. The binding never invents synonyms.
- **Public = documented API surface.** Everything else package-private or under
  `internal`. A new public member is a deliberate API decision, not a default.
- **Immutability by default**; builders only where many optional fields genuinely
  warrant them (`LaminarConfig` — nowhere else without a plan reference).
- **Nullability**: parameters validated with `Objects.requireNonNull` at public
  boundaries; no `Optional` on hot paths; never return null collections (return empty).
- **Resources**: try-with-resources for every `AutoCloseable` use, in production code
  *and* in docs examples (docs-as-test makes this enforceable, plan 02 §6).
- **Size discipline** (comprehension limits, from the main repo's readability rules):
  methods 20–60 logical lines ordinary, > 80 review warning, > 120 requires an inline
  justification; classes ~200–400 lines ordinary, > 600 warning. Extract by concept,
  never to satisfy counts — no one-line wrappers.

## 4 — AI-slop checklist (each item is REQUEST CHANGES)

- **Speculative generality**: an interface with one implementation and no SPI decision
  on record (the sanctioned SPIs are `internal.Binding` and nothing else); factories
  wrapping constructors; options/flags/parameters no caller or test uses.
- **Placeholder code shipped**: `TODO`/`FIXME`/stub bodies on shipped paths;
  `UnsupportedOperationException` anywhere not explicitly planned (the only sanctioned
  uses are the network-binding write paths deferred to Phase 3, plan 05 §2).
- **Defensive noise**: null checks on values our own native contract guarantees
  non-null; catch-wrap-log-rethrow chains; empty catch blocks; catching `Exception`
  wider than the documented failure set for that call.
- **Copy-paste artifacts from the Python binding**: snake_case leftovers, context-
  manager/`__del__` semantics misapplied to Java, Python iterator-protocol remnants.
  The Python repo is a *reference for invariants*, not a template to transliterate.
- **Commented-out code** and changelog/attribution-style comments in code.
- **Terminology drift** from §3, or two names for one concept introduced by accident.

## 5 — In-code documentation policy (the anti-excessive rule)

- **Public API**: Javadoc required on every public class and member. First sentence
  usable as a summary; `@param`/`@return`/`@throws` only where non-obvious; blocking
  behavior and thread-safety stated where they apply (plan 02 §1 already mandates this).
- **Private/internal code**: comments only for constraints the code cannot express —
  ownership rules, JNI lifetime boundaries, invariants. Use the repo's labels
  (`INVARIANT:`, `WHY:`, `SAFETY:`, `PERF:`) where they aid scanning. Rust glue mirrors
  the main repo's rule: concise reasoning, never narration.
- **Prohibited everywhere**: comments restating a method name or the next line;
  Javadoc on private members that adds nothing over the name; `@throws` for exceptions
  that cannot actually be thrown; essays where a sentence does; documentation of
  *planned* behavior that the code does not do (that belongs in `docs/plans/`).

The test for every comment: delete it — if the code is harder to *verify*, it stays; if
only harder to *skim*, it goes.

## 6 — Unwired/unused code

- The JaCoCo zero-coverage rule (§2) is the automated net: a production class nothing
  exercises anywhere fails the build. Every JaCoCo exclusion must carry a written
  justification next to the exclusion entry.
- Review rule: **every public API member is exercised by at least one test and
  referenced by the docs or a plan.** A public member that only exists "for
  completeness" is dead weight — remove it.
- `io.laminardb.internal.Native` methods with no Java caller fail review; the JNI
  surface is a maintained contract (plan 02 §2), not a junk drawer that grew during
  development.
- Rust: `#[allow(dead_code)]`/`#[allow(unused)]` without a justification naming the
  phase that will consume the item fail the grep gate; unused dependencies fail
  `cargo machete`.
- Feature-flags/config knobs follow the same rule as plan 02's `LaminarConfig`: map a
  native knob only when a caller exists for it in the same phase.

## 7 — Test discipline (anti-excessive AND anti-weak)

- Tests assert **observable behavior through the public API** only: no reflection into
  private state, no asserting interactions with the native layer — it cannot be
  meaningfully mocked, because the crossing *is* the thing under test.
- **No duplicated core guarantees**: SQL semantics, window math, and join correctness
  are the core's test suite's job. Binding tests cover crossing fidelity, type
  conversion, error mapping, lifecycle, threading, and leak accounting.
- **Table-driven over permutation bloat**: converter and error-mapping matrices use
  `@ParameterizedTest` data tables, not copy-paste variants of the same assertion.
- **Timing**: bounded deadline-waits only (Awaitility or a committed helper);
  `Thread.sleep`-based assertions fail review. Every long-running test loop shows its
  termination bound (main-repo rule).
- Every test has at least one assertion of its own (allocator-zero `@AfterAll` helpers
  don't count). A test whose failure mode is "times out" or "throws unexpectedly" is a
  weak test — assert the specific behavior.
- **Coverage is a floor detector (§6), not a quota**: writing tests to move a
  percentage is itself a review finding. Optional spot tool when conversion/error
  packages grow: PIT mutation testing on those packages only.
- Test-count is not a virtue: deleting a redundant test is a valid, reviewable change.

## 8 — Review process

- **Per PR**: CI gates (`just verify` + `just review`) green, plus a reviewer — human
  or an agent running `agents/code-review.md` — applying §3–§7 to the diff. Verdict
  recorded on the PR: APPROVE or REQUEST CHANGES with findings.
- **Per phase exit** (plans 01/02/03 acceptance checklists): a full-tree review pass
  against this plan, recorded in `docs/reviews/<phase>-<date>.md` — findings by
  category ([STRUCTURE]/[SLOP]/[DOCS]/[DEAD]/[TESTS]/[INVARIANT]) and their
  resolutions. A phase cannot ship with open REQUEST CHANGES findings.
- **Reviewer agents**: `agents/code-review.md` is harness-neutral (paste its content as
  the reviewer's instructions), mirroring the main repo's `agents/*.md` pattern. The
  prompt assumes read access to this repo only; its verdicts are checked against
  `just verify` and `just review` outputs.
- Escalation: disagreements about a finding resolve against the plans — if the plans
  are wrong, fixing the plan is part of the PR that proved it (plan 00 §5 rule).

## 9 — Wiring summary

| Phase | Gates that must exist | Gates that must be green to exit |
|---|---|---|
| 0 | fmt/clippy/machete/allows-grep, Spotless, Checkstyle, `agents/code-review.md` committed | all Phase-0 gates + phase review record |
| 1 | + SpotBugs, JaCoCo zero-coverage rule, `docs/reviews/` convention | all prior + full §4–§7 pass on the shipped surface |
| 2 | + (optional) Error Prone, PIT spot runs | same standard, applied to subscription/callback surface |
| release (plan 04) | — | latest phase review record shows zero open findings |

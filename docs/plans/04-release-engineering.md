# Plan 04 — Release engineering: packaging, cross-compilation, publishing

Status: **Wired (2026-08-29); first actual release blocked on maintainer credentials** · Read before exiting Phase 1 (its artifact layout constrains Phase 0/1 build structure). Owns everything between "tests green" and "a user in a bare
Maven project succeeds".

## 1 — Artifact layout

| Artifact | Contents | When |
|---|---|---|
| `io.laminardb:laminardb` (the only one users need) | Java classes + `/natives/<os>-<arch>/` cdylib for every supported platform, extracted at first load | Phase 1 |
| `io.laminardb:laminardb-core` + `<classifier>` jars | Java classes only / one native per classifier — slim Docker images | Phase 2+, only if users ask (D3) |
| `io.laminardb:laminardb-bom` | — | Only when a second module (e.g. `laminardb-json`) exists |

Platform directory keys: `linux-amd64`, `linux-aarch64`, `macos-amd64`, `macos-aarch64`,
`windows-amd64` (Phase 2). Library file names: `liblaminar_java.so`, 
`liblaminar_java.dylib`, `laminar_java.dll`.

## 2 — `NativeLoader` (full spec)

Resolution order (first hit wins; every step logged at debug via `java.util.logging` —
no logging framework dependency at compile scope):

1. System property `laminardb.native.path` — absolute path to the library file
   (power users, reproducible environments).
2. Classpath resource `/natives/<os>-<arch>/<lib-name>` — extracted to
   `${java.io.tmpdir}/laminardb-native/<version>/<lib-name>` (version-scoped so
   concurrent apps with different versions don't collide); extraction is
   write-to-temp + atomic rename; a SHA-256 sidecar check skips re-extraction when
   the file already matches. On Linux, prefer extracting to `${user.home}/.cache/`
   when tmpdir is mounted `noexec` — detect via exec probe and fall back, with an
   actionable error message naming the `laminardb.native.path` escape hatch.
3. `System.loadLibrary("laminar_java")` — `java.library.path` fallback.

Failure message (the dev-friendliness contract): names the detected os-arch, the
platforms actually bundled in the jar, and the three resolution mechanisms. This
message is the one thing every unsupported-platform user reads — test it.

JDK 24+ note in Javadoc + README: without `--enable-native-access=ALL-UNNAMED` the JVM
prints a warning (JNI and FFM alike, JEP 472); functionality is unaffected today.

## 3 — Versioning policy (D4)

- Binding version **tracks the core**: core `v0.31.2` ⇒ binding `0.31.2`.
- Binding-only changes bump patch beyond the core (`0.31.3` with core still pinned at
  `v0.31.2`) — allowed only while pre-1.0; record the (binding version, pinned core
  tag) pair in a `CORE_PIN.md` table maintained by the release workflow.
- Pre-release channel: `-alpha` (Phase 1), `-beta` (Phase 2), plain (post-Phase 2).
- The release gate (§5) enforces: git tag == `Cargo.toml` version == `pom.xml` version,
  and `Cargo.toml`'s `laminar-db` dependency is a **git tag** (regex-enforced; branch
  pins fail the release — the Python repo's reproducibility mistake, structurally
  prevented).

## 4 — Cross-compilation matrix

Toolchain: `cargo-zigbuild` for Linux targets from any host; native runners for macOS
and Windows (Apple silicon runner for darwin-aarch64; darwin-amd64 via
`macos-13`-class runner or `--target x86_64-apple-darwin` universal logic — use plain
per-target builds, no universal2 fat binaries).

| Target | glibc/toolchain baseline | Runner | Phase |
|---|---|---|---|
| `x86_64-unknown-linux-gnu` | 2.28 (zigbuild `--glibc 2.28`, manylinux_2_28 parity with the Python wheels) | ubuntu-latest | 1 |
| `aarch64-unknown-linux-gnu` | 2.28 | ubuntu-latest + qemu or native arm runner | 1 |
| `aarch64-apple-darwin` | runner SDK | macos-latest | 1 |
| `x86_64-apple-darwin` | runner SDK | macos-intel-class | 1 |
| `x86_64-pc-windows-msvc` | MSVC | windows-latest | 2 |

Each build job compiles `--release`, strips symbols (profile already sets
`strip = "symbols"`), and runs the **full JUnit suite against the cross-built artifact
via qemu-user** for Linux arm64 (and natively elsewhere) — no platform ships untested.

## 5 — Release workflow (`.github/workflows/release.yml`)

Trigger: tag `v*`. Jobs, in order:

1. **validate**: version parity (git tag / Cargo.toml / pom.xml), core-pin-is-a-tag
   regex check, CHANGELOG entry exists for the version, and the latest phase review
   record in `docs/reviews/` (plan 06 §8) shows zero open REQUEST CHANGES findings.
2. **build-native** (matrix per §4): cargo-zigbuild/native build → upload
   `liblaminar_java-<target>` artifact.
3. **assemble-and-test**: download all natives, `mvn -Dnatives.dir=… package` producing
   the fat jar, run the JUnit matrix against the packaged jar (Java 17/21/21-virtual/
   25 matrix), run the `QuickstartIT` from a **bare** `mvn archetype:generate` project
   depending only on the built jar.
4. **publish**: GPG-sign (secret-stored key), publish to Maven Central via the Central
   Portal (`central-publishing-maven-plugin`; namespace `io.laminardb` verified/owned
   once, manually, before first release — one-time setup task, do it in Phase 0 to
   remove it from the critical path: ______ done).
5. **verify-publish**: poll Central for the artifact's resolvability (the Python repo's
   failed-PyPI-publish failure mode, structurally caught); comment the release tag with
   the verified coordinates.

   Wired status: release.yml implements jobs 1–6 (assemble-and-test runs the
   suite against the packaged jar and the bare-project quickstart via
   `scripts/bare-quickstart.sh`; the pom's `central` profile carries
   central-publishing/source/javadoc/gpg plugins). **Blocker (one-time,
   maintainer-only):** Central Portal namespace ownership for `io.laminardb`,
   GPG key upload, and the `maven-central` GitHub environment (secrets
   `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, with required
   reviewers). The publish job fails with a recorded error until these
   exist — deliberately, so no release ships unverified.
6. **github-release**: notes from CHANGELOG section, jar + checksums attached.

Smoke-on-clean-env: step 3's bare-project test must run `mvn test` offline-of-this-repo
(exercises `NativeLoader` extraction end-to-end on each OS).

## 6 — Core bump procedure (routine, PR-template'd)

1. Update `Cargo.toml` pin to the new core tag; bump versions (Cargo + pom) to match.
2. `cargo update -p laminar-db` … full `just verify` + extended platform matrix.
3. The Rust `codes` coverage test (plan 02 §5) forces error-mapping awareness of new
   codes; exhaustive `match`es force subscription-frame awareness (plan 03 §1).
4. CHANGELOG "Updated the LaminarDB core to X" + `CORE_PIN.md` row; conventional
   commit `chore(core): bump to vX.Y.Z`.

## 7 — Supply chain

- [ ] `cargo deny` (licenses/duplicates/advisories) in CI; `cargo audit` nightly.
- [ ] SBOM (CycloneDX) for the fat jar attached to each GitHub release (maven plugin).
- [ ] Pin actions by full SHA in both workflows.
- [ ] Signing keys and Central Portal credentials in GitHub environments with required
      reviewers on the release environment (manual approval gate before publish).

## Acceptance checklist

- [ ] First `-alpha` release passes steps 1–6 end to end with no manual fixups.
- [ ] A bare project with the single dependency runs the quickstart on all four
      Phase-1 platforms (verified in-workflow, and once by hand per platform).
- [ ] `CORE_PIN.md` and CHANGELOG accurate; tag/versions/pin gate proven to fail on a
      deliberately broken dry-run (test the gate itself once).

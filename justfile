# Build orchestration for laminardb-java. `cargo` builds the cdylib, `mvn`
# builds/tests the jar; this file owns the wiring between them.

default:
    @just --list

# Build the Rust cdylib and stage it under target-native/debug/.
build:
    cargo build
    mkdir -p target-native/debug
    cp target-rust/debug/liblaminar_java.* target-native/debug/

# Build + run the JUnit suite against the staged cdylib.
test: build
    mvn test

# Correctness gate: fmt + clippy + Rust unit tests + JUnit.
verify:
    cargo fmt --check
    cargo clippy --all-targets -- -D warnings
    cargo test
    just test

# Review gate (plan 06 §2): Phase-0 tooling + SpotBugs + JaCoCo zero-coverage.
# mvn verify runs the test suite, so the cdylib must be staged first.
review: build
    cargo fmt --check
    cargo clippy --all-targets -- -D warnings
    cargo machete
    just allows-grep
    mvn spotless:check checkstyle:check verify

# Every `#[allow(...)]` in src/ must carry an inline `WHY:` justification.
allows-grep:
    @! grep -rn '#\[allow(' src/ | grep -v 'WHY:'

# JMH suite (plan 03 §5); append results to docs/benchmarks.md manually.
bench: build
    cd benchmarks && mvn -q package && java -Djava.library.path=../target-native/debug \
        -jar target/benchmarks.jar -f 1 -wi 2 -i 3 || true

clean:
    cargo clean
    mvn clean
    rm -rf target-native

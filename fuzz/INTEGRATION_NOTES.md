# fuzz/ — Engineering Notes & Integration Log

_Last updated: 2026-06-10_

This document records the work done on the `fuzz/` harness: what was added, the
design decisions behind it, and — importantly — the unresolved issues with
integrating **SPE** (Skeletal Program Enumeration, a.k.a. `printSkeletalPrograms`)
into the generate-and-run flow (`genAndRun`).

It is both a changelog and a decision record, so future work doesn't have to
re-derive the constraints.

---

## 1. Overview of the `fuzz/` harness

Shell-driven fuzzing harness around [wgslsmith]. A seed → a generated WGSL
shader → some processing → a verdict. Failing cases are kept on disk for triage.

| Script         | Role |
| -------------- | ---- |
| `genAndRun`    | gen + recondition one shader, then **execute** it (`wgslsmith run`). |
| `glslangCheck` | gen + recondition, translate to GLSL with **both tint and naga**, validate each with **glslang**. |
| `skeletalRun`  | gen, enumerate **N random skeletal variants** (SPE), then recondition + run each + the original. **Currently blocked — see §6.** |
| `runLoop`      | drive any of the above over many seeds, by **count** or by **duration**. |
| `common.sh`    | sourced by all: tool resolution, config loading, result logging. |

`genAndRun`, `glslangCheck`, and `skeletalRun` all share the `<seed> [config]`
signature, which is what lets `runLoop -r` swap between them.

---

## 2. `glslangCheck` (added)

New per-seed runner that stresses the WGSL→GLSL translators and the glslang
front end instead of executing the shader.

Pipeline: `wgslsmith gen` → `recondition` → translate to GLSL with **tint**
(`tint --format glsl`, emits `#version 310 es`, validated `glslang -S comp`)
**and naga** (`naga … --shader-stage compute --entry-point main --profile es310`,
also 310, validated `glslang -S comp`).

- Reuses `run_dir`/`load_config`/`write_meta`. On full pass, artifacts deleted
  (only `meta.json` kept); on failure, all artifacts + `check.log` retained.
- Failure categories distinguish which front end disagreed:
  `tint-glslang-fail`, `naga-glslang-fail`, `glslang-fail-both` (a disagreement
  is the most interesting signal).
- **Assumption:** wgslsmith output is a single `@compute fn main`, so stage/entry
  are hardcoded to compute/`main`. Documented in `fuzz/README.md`.

---

## 3. `runLoop` enhancements

Two orthogonal additions:

### 3a. Runner selection (`-r/--runner`)
`runLoop [-r <runner>] …` invokes any executable in `fuzz/` with the shared
`<seed> [config]` signature (default `genAndRun`). Validates the runner exists;
errors cleanly otherwise.

### 3b. Duration mode (`-d/--duration`)
Two stopping modes now:
```
runLoop [-r <runner>] <start_seed> <count> [config]          # count mode
runLoop [-r <runner>] -d <duration> <start_seed> [config]    # duration mode
```
`-d` accepts `90s`, `30m`, `5h`, `2d`, combined `1h30m`, or bare seconds.

**Standard timed-eval semantics:** the deadline is checked **between** seeds, so
an in-flight seed always finishes rather than being killed mid-run ("at least
this long", not a hard cutoff). Summary prints pass/fail, elapsed time, and the
seed range covered.

To run for 5h on `genAndRun` (the wgslsmith executor): `fuzz/runLoop -d 5h 1`
(use `tmux`/`nohup` so it survives disconnects).

---

## 4. Multi-machine & open-source structure

The repo must run on several machines and eventually be open-sourced, so
**tracked code carries no machine-specific absolute paths.**

### Tool resolution (`common.sh`)
Each external tool resolves in priority order:
1. explicit environment variable (or `common.local.sh`),
2. else a `PATH` lookup (`command -v`).

No absolute defaults are baked in. Validation is **lazy** via `require_tool`, so
a runner only fails if a tool it actually uses is missing — `genAndRun` needs
only `WGSLSMITH`; `glslangCheck`/`skeletalRun` need more.

```sh
WGSLSMITH="${WGSLSMITH:-$(command -v wgslsmith || true)}"
TINT="${TINT:-$(command -v tint || true)}"
NAGA="${NAGA:-$(command -v naga || true)}"
GLSLANG="${GLSLANG:-$(command -v glslang || true)}"
```

### Per-machine overrides
- `fuzz/common.local.sh` — gitignored; sourced first if present. Holds machine
  paths for tools not on `PATH`.
- `fuzz/common.local.sh.example` — **tracked** template; users `cp` it and edit.
- Discovery for someone who pulls: the `.example` (tracked) + `common.sh`
  comment + `fuzz/README.md` + the `require_tool` error message all point to it.
- `.gitignore` gained `fuzz/common.local.sh`.

Key fact established: a gitignored file is **never pushed** (push only sends
tracked files). But `.gitignore` does not untrack a file already committed, and
it cannot scrub history — which is why machine paths were removed from tracked
files now, before open-sourcing. (`scripts/genAndRun` and `scripts/validateGlsl`
still contain `/home/xyl25` paths — left alone for now by request.)

### Why aliases don't work in scripts (recurring gotcha)
A script run does `fork`+`execve`; binaries run directly, shell scripts get a
**non-interactive, non-login** bash that sources **no** startup files
(`.bashrc`/`.bash_profile`). Environment variables (`PATH`, `WGSLSMITH`) cross
`exec`; **aliases and shell functions do not**. Hence: alias = useless to
scripts; `PATH` entry = works if exported where non-interactive shells see it;
`/usr/bin` (or `/usr/local/bin`) symlink = works everywhere; `common.local.sh` =
most robust here because the script `source`s it explicitly.

---

## 5. `meta.json` storage analysis (discussed, NOT yet implemented)

Question raised: is writing a per-seed `meta.json` for every seed wasteful?

Findings (measured on ~995 seed dirs):
- A `meta.json` is ~148 bytes logically, but a seed directory costs ~8 KB on
  disk (4 KB block rounding + the directory itself). 995 seeds ≈ 8 MB.
- **Bytes don't explode** (a 5h `genAndRun` ≈ ~1,800 seeds ≈ ~14 MB; ~18k seeds
  ≈ ~140 MB), but **inodes/clutter do** — 2 inodes per seed, unbounded; slow
  `ls`/`find`, painful backups.
- For a **passing** seed, `meta.json` is pure duplication of the matching line
  in `runs/<config>/results.jsonl` (they're byte-identical), and the seed dir is
  otherwise empty (artifacts deleted). It's only genuinely useful for failures,
  where it sits next to retained artifacts.

**Proposed (not done):** on pass, append to `results.jsonl` and remove the seed
directory entirely; keep `meta.json` + dir only on failure. Collapses the common
case to a single append-only file. `write_meta` would split into "always append
to results.jsonl" + "write meta.json only when keeping the dir".

---

## 6. SPE ↔ `genAndRun` integration (`skeletalRun`) — **issues**

This is the requested focus. The goal was: take a wgslsmith-generated shader,
enumerate it randomly N times via SPE (`printSkeletalPrograms`), then run
wgslsmith on the enumerated variants plus the original.

### 6.1 Intended pipeline (ordering chosen by user)
**Enumerate from the raw generated shader, then recondition each variant:**
```
wgslsmith gen                      -> gen.wgsl
printSkeletalPrograms --random --limit N   (on gen.wgsl)
  -> skeleton_000.wgsl ... skeleton_00N.wgsl
for each {gen.wgsl, skeleton_*}:
    wgslsmith recondition          -> rc
    wgslsmith run                  rc
```
(`wgslsmith run` needs a reconditioned shader for loop/UB safety, hence the
per-variant recondition.)

### 6.2 Changes made (all within allowed paths)
**Constraint:** only edit `src/main/kotlin/com/wgslspe/`; `com/wgslfuzz/` (core:
`AstWriter`, `Parser`, `ShaderJob`) is **off-limits**.

`src/main/kotlin/com/wgslspe/tools/PrintSkeletalPrograms.kt`:
- Added `--random` flag → calls `getSkeletons(tu, env, n=limit, random=random)`
  (previously hardcoded `random=false` and ignored `n`).
- Added `--parse-timeout <ms>` (default 10000) → passed to `createShaderJob`.
- Added `emitSkeleton()` that captures `AstWriter` output to a string and strips
  AstWriter trailing commas before writing (see 6.4). `com/wgslfuzz` untouched.

`fuzz/common.sh`:
- `REPO_ROOT` + `GRADLEW` (Gradle must run from repo root).
- `SKELETONS` (default 8, env-overridable) and `PARSE_TIMEOUT` (default 60000,
  env-overridable) added to `load_config`.

`fuzz/skeletalRun` (new): implements 6.1; per-variant pass/fail tracking; on full
pass cleans up, on any failure keeps `gen.wgsl`, the enumeration tree, and each
failing variant's `.rc.wgsl`/`.log`. Categories: `recondition-failed:<name>` or
`<run-category>:<name>`. `runLoop -r skeletalRun` works.

### 6.3 Issue A — WGSL parse timeout (RESOLVED)
The JVM tool parses the input via `createShaderJob`, which has a **hardcoded 10s
parse timeout** (`DEFAULT_PARSE_TIMEOUT_MILLISECONDS = 10000`). Raw wgslsmith
output has deeply-nested expressions that are pathological for the ANTLR parser.

Measured hit rate on 8 raw shaders: **4/8 exceeded 10s** (seeds 1,3,4,7 failed;
2,5,6,8 ok). Reconditioning **does not help** (reconditioned versions of the
failing seeds also timed out) — so the recondition-order decision is irrelevant
to this. With a 60s budget the failing shaders parse fine (~14s) → the parse is
**slow-but-finite**.

**Fix:** `--parse-timeout` knob (6.2), surfaced as `PARSE_TIMEOUT` (default
60000 ms) in the harness.

### 6.4 Issue B — AstWriter vs wgslsmith dialect mismatch (**BLOCKING**)

#### Root cause: a round-trip through *two different parsers*
The natural question is: "`wgslsmith recondition` accepts `wgslsmith gen` output,
and the enumerator only swaps variables — so why would its output stop parsing?"
The answer is that `printSkeletalPrograms` does **not** textually edit
`gen.wgsl`. It re-renders the whole program through a *different* parser/writer:

```
wgslsmith gen ──► gen.wgsl                (wgslsmith dialect text)
                    │  read by wgsl-fuzz's OWN parser (ANTLR/Kotlin)
                    ▼
                  AST (TranslationUnit)
                    │  swap variables on the AST (cloneWithName)
                    ▼  re-serialized by AstWriter
              skeleton_NNN.wgsl            (AstWriter dialect text — NOT gen.wgsl)
                    │  read by wgslsmith's parser (Rust)
                    ▼
              recondition / run            ✗ rejects AstWriter dialect
```

There are **two parsers** in play, not one:
1. **wgslsmith's parser** (Rust) — reads `gen` output and `recondition`/`run`
   input. `gen → recondition` works because both ends are wgslsmith's dialect.
2. **wgsl-fuzz's parser** (ANTLR, inside `printSkeletalPrograms`) — reads the
   shader into an AST.

The original concrete syntax of `gen.wgsl` is **discarded the moment it becomes
an AST** (the AST stores structure, not formatting). `AstWriter` then regenerates
the *entire* program's text in *its own* style — applied uniformly to every
construct, swapped or not. So the variable swap is incidental: an *identity*
enumeration (swap nothing) would still produce AstWriter-dialect text that
wgslsmith rejects. The round-trip is the cause.

**Proof in our own data:** the original `gen.wgsl` had `case 0i: {`; the skeleton
had `case 0i,\n{`. That selector is the literal `0i` — *not* a swapped variable —
yet the colon vanished and a trailing comma appeared. Only the
`wgsl-fuzz parse → AstWriter emit` round-trip could have done that.

#### It is a grammar difference, not a formatting difference
Whitespace/newlines/indentation are irrelevant — WGSL is free-form and the
tokenizer uses maximal munch (`f32}` ≡ `f32 }` ≡ `f32\n}`). The differences are
**token-level**, and wgslsmith's parser is **not a full WGSL parser**: it accepts
only the narrow dialect wgslsmith itself emits, a strict subset of valid WGSL:

```
valid WGSL (spec)  ⊋  what wgslsmith's parser accepts
```

`AstWriter` targets the spec (uses spec-permitted optional forms); wgslsmith
reads with the subset. Skeletons land in the gap — valid WGSL that wgslsmith
refuses. Concretely:

| Construct | wgslsmith writes / accepts | `AstWriter` emits | Status |
| --------- | -------------------------- | ----------------- | ------ |
| int min   | `i32(-2147483648)`         | `i32(-2147483648, )` (trailing comma) | **Fixed** — strip |
| struct body | `d: f32,` then `}` (comma is a *terminator*, required) | `d : f32, }` | **Fixed** — exclude `}` from strip |
| switch `case` | `case 0i: {` | `case 0i,` then `{` (trailing comma, **colon omitted**) | **Unfixed** |
| `default` | `default: {` | `default` then `{` (**colon omitted**) | **Unfixed** |

The trailing-comma cases were handled with a regex in `PrintSkeletalPrograms`
(stays in `com/wgslspe`):
```kotlin
// strip a comma immediately before ) ] > or a case ':' — but NOT before '}'
private val TRAILING_COMMA = Regex(",\\s*(?=[)\\]>:])")
```
But the `case`/`default` **colon** is a missing *token*, not a stray one: the
colon is optional in the WGSL spec so `AstWriter` omits it, while wgslsmith
requires it. No reformatting produces a token that isn't in the stream; it must
be inserted. Doing that textually (insert colon, drop selector trailing comma,
handle multi-selector lists) is possible but brittle — and there is no guarantee
the next construct won't differ too, since I'm effectively re-implementing a
WGSL-spec→wgslsmith-dialect translator after the fact.

**Current state of `skeletalRun`:** mechanically end-to-end (gen → enumerate →
recondition → run all wired), Issue A resolved, but **blocked by Issue B** — on
seed 1, enumeration succeeds and the original shader runs (it fails with the
pre-existing `spirv-layout-decoration` backend bug), but every skeleton fails to
recondition because of the `case`/`default` colon dialect difference.

### 6.5 The clean fix (out of bounds)
The real fix is for the writer to emit wgslsmith's dialect in the first place —
one fix at the source, no whack-a-mole. That writer is `AstWriter` in
`com/wgslfuzz/`, which is off-limits per the editing constraint. So the blockage
is a policy/architecture decision, not a coding gap: either `AstWriter` is
allowed to change, or the skeletons must reach a runner that uses wgsl-fuzz's own
parser (so the round-trip stays within one dialect — see option 3 below).

### 6.6 Open decision (awaiting direction)
1. **Keep patching in `wgslspe`** — extend the normalization to rewrite
   `case`/`default` into colon form. Works now; brittle to future AstWriter
   changes and untested constructs.
2. **Fix `AstWriter`** — lift the constraint for this; one correct fix in the
   shared core.
3. **Rethink the coupling** — the enumerate→wgslsmith-recondition path feeds
   AstWriter output into wgslsmith; a wgslsmith-native serializer, or a different
   runner for skeletons, may be a better design. (Note: `executePipeline` avoids
   this entirely — it runs skeletons via the in-JVM Dawn harness, which uses
   `AstWriter` + wgsl-fuzz's *own* parser, never feeding them back to wgslsmith.)

---

## 7. File inventory (this work)

Added:
- `fuzz/glslangCheck`, `fuzz/skeletalRun`
- `fuzz/common.local.sh.example`
- `fuzz/README.md`, `fuzz/INTEGRATION_NOTES.md` (this file)

Modified:
- `fuzz/common.sh` — tool resolution, `require_tool`, `REPO_ROOT`/`GRADLEW`,
  `SKELETONS`/`PARSE_TIMEOUT`
- `fuzz/genAndRun` — `require_tool WGSLSMITH`
- `fuzz/runLoop` — `-r` runner selection, `-d` duration mode
- `.gitignore` — `fuzz/common.local.sh`
- `src/main/kotlin/com/wgslspe/tools/PrintSkeletalPrograms.kt` — `--random`,
  `--parse-timeout`, trailing-comma normalization

Left alone by request: `scripts/*` (still has hardcoded paths),
`com/wgslfuzz/*`, and the §5 `meta.json` optimization.

[wgslsmith]: https://github.com/wgslsmith-dev/wgslsmith

# fuzz/

Shell-driven fuzzing harness for WGSL shaders. It generates random shaders
with [wgslsmith](https://github.com/wgslsmith-dev/wgslsmith), then either
executes them across backends or pushes them through the WGSL→GLSL translators
and validates the result. Failing cases are kept on disk for triage.

## Scripts

| Script         | What it does                                                                 |
| -------------- | --------------------------------------------------------------------------- |
| `genAndRun`    | Generate + recondition one shader, then **execute** it (`wgslsmith run`).    |
| `glslangCheck` | Generate + recondition one shader, translate it to GLSL with **both tint and naga**, and validate each output with **glslang**. |
| `runLoop`      | Drive `genAndRun` or `glslangCheck` over many seeds, by count or by time.    |
| `common.sh`    | Sourced by the others: tool resolution, config loading, result logging.     |

`genAndRun` and `glslangCheck` both take the same `<seed> [config]` arguments,
which is why `runLoop` can swap between them.

## Prerequisites

Depending on which runner you use, you need some of these external tools:

| Tool       | Used by                    | Where it comes from                                  |
| ---------- | -------------------------- | ---------------------------------------------------- |
| `wgslsmith`| `genAndRun`, `glslangCheck`| [wgslsmith](https://github.com/wgslsmith-dev/wgslsmith) (`cargo build`) |
| `tint`     | `glslangCheck`             | [Dawn](https://dawn.googlesource.com/dawn) build (`out/<cfg>/tint`)     |
| `naga`     | `glslangCheck`             | [wgpu](https://github.com/gfx-rs/wgpu)'s `naga` CLI (`cargo build`)     |
| `glslang`  | `glslangCheck`             | [glslang](https://github.com/KhronosGroup/glslang); a sanitizer-instrumented build is recommended |

`genAndRun` needs only `wgslsmith`; `glslangCheck` needs all four. Each script
validates the tools it actually uses and prints an actionable error if one is
missing, so you won't get cryptic failures.

## Setup

The repo contains **no machine-specific paths**. Each tool is located in this
order:

1. an explicit environment variable (`WGSLSMITH`, `TINT`, `NAGA`, `GLSLANG`);
2. otherwise, a lookup on your `PATH`.

So if the four tools are on your `PATH`, there is nothing to configure.

If some tools live in nonstandard locations, copy the example overrides file
and edit it (it is gitignored, so your paths never get committed):

```bash
cp fuzz/common.local.sh.example fuzz/common.local.sh
$EDITOR fuzz/common.local.sh
```

Or override per-invocation / in CI without any file:

```bash
GLSLANG=/opt/glslang/build/StandAlone/glslang fuzz/runLoop -r glslangCheck -d 1h 1
```

## Usage

Run everything from the repository root.

### Single seed

```bash
fuzz/genAndRun    42            # execute shader for seed 42, "default" config
fuzz/glslangCheck 42            # translate + validate shader for seed 42
fuzz/genAndRun    42 naga-only  # use configs/naga-only.env
```

### Many seeds (`runLoop`)

```bash
# count mode: a fixed number of consecutive seeds
fuzz/runLoop                 1 100            # genAndRun, seeds 1..100
fuzz/runLoop -r glslangCheck 1 100            # glslangCheck, seeds 1..100

# duration mode: keep incrementing the seed until a wall-clock budget elapses
fuzz/runLoop -r glslangCheck -d 5h  1         # run for 5 hours from seed 1
fuzz/runLoop                 -d 30m 1000 dawn-only
```

`-d/--duration` accepts `90s`, `30m`, `5h`, `2d`, combined forms like `1h30m`,
or a bare number of seconds. The budget is checked between seeds, so an
in-flight seed always finishes rather than being killed mid-run (the standard
"at least this long" convention for timed evaluation runs).

Individual failures never stop a `runLoop`; it prints a pass/fail summary (and,
in duration mode, the elapsed time and seed range covered) at the end.

## Configs

Configs live in `configs/<name>.env` and are selected by name (default:
`default`). Each sets three variables:

| Variable    | Meaning                                                             |
| ----------- | ------------------------------------------------------------------ |
| `CONFIGS`   | backend selector for `wgslsmith run -c` (empty = all defaults). Used by `genAndRun`. |
| `TIMEOUT`   | per-execution timeout in seconds (`0` disables). Used by `genAndRun`. |
| `GEN_FLAGS` | extra flags passed to `wgslsmith gen`. Used by both runners.        |

Provided configs:

- `default` — all backend defaults (`dawn:vk` and `wgpu:vk`).
- `dawn-only` — Dawn/Tint backend only, on SwiftShader.
- `naga-only` — wgpu/naga backend only, on SwiftShader.

`glslangCheck` only uses `GEN_FLAGS` (it doesn't execute, so `CONFIGS`/`TIMEOUT`
don't apply).

## Output

Artifacts go to `runs/<config>/<seed>/` (gitignored). On success the source
files are deleted and only a `meta.json` record is kept; on failure all
artifacts are retained for triage:

- `genAndRun`: `gen.wgsl`, `rc.wgsl`, `run.log`
- `glslangCheck`: `gen.wgsl`, `rc.wgsl`, `tint.comp`, `naga.comp`, `check.log`

Every run also appends a one-line JSON record to `runs/<config>/results.jsonl`
with the seed, status, and failure category — handy for aggregating a campaign:

```bash
# count failures by category for a config
grep '"status":"fail"' runs/default/results.jsonl \
  | grep -o '"category":"[^"]*"' | sort | uniq -c
```

`glslangCheck` failure categories distinguish which front end rejected the
shader: `tint-glslang-fail`, `naga-glslang-fail`, or `glslang-fail-both` (a
disagreement between the two is the most interesting signal).

## Assumptions

`glslangCheck` assumes wgslsmith's current output shape: a single `@compute`
entry point named `main`. tint/naga/glslang are therefore all driven in compute
mode (`--shader-stage compute --entry-point main`, `glslang -S comp`). If
non-compute or differently-named entry points are ever generated, the entry
point and stage in `glslangCheck` need to be derived from the WGSL instead.

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

Only `default` is shipped in the repo (`CONFIGS=""`, i.e. wgslsmith's platform
defaults — portable to any machine). Every other config is **machine-specific**
and lives only on your machine, because the backend IDs in `CONFIGS` depend on
the GPUs/harness present. `configs/*.env` is gitignored except `default.env`.

### Creating your own configs

The backend IDs come from `wgslsmith harness list`, e.g.:

```
dawn:vk:9654 | NVIDIA A16
dawn:vk:0    | llvmpipe (LLVM 20.1.2, 256 bits)
```

Rather than hardcoding those IDs in every config, define them **once** as
`CFG_*` variables in `common.local.sh` (gitignored), then reference them
symbolically from your configs. `common.local.sh` is sourced before any config,
so the variables are in scope. Copy the template to get started:

```bash
cp fuzz/common.local.sh.example fuzz/common.local.sh   # has a CFG_* section
```

```bash
# fuzz/common.local.sh
CFG_NVIDIA_DAWN=dawn:vk:9654
CFG_NVIDIA_WGPU=wgpu:vk:9654
```

```bash
# fuzz/configs/nvidia.env  (local, gitignored)
CONFIGS="$CFG_NVIDIA_DAWN $CFG_NVIDIA_WGPU"   # compare Dawn vs wgpu on the A16
TIMEOUT=120
GEN_FLAGS=""
```

Now an adapter-ID change is a one-line edit in `common.local.sh` rather than a
hunt through every config. Two forms are useful when referencing a `CFG_*`:

- `"${CFG_SW_DAWN:-dawn:vk:49374}"` — fall back to a literal if unset (the
  shipped `dawn-only`/`naga-only` examples use this so they work out-of-box).
- `"${CFG_NVIDIA_DAWN:?define CFG_NVIDIA_DAWN in common.local.sh}"` — fail fast
  with a clear message if you forgot to define it (scripts run under `set -u`).

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

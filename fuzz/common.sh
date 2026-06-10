# common.sh — shared helpers for the fuzzing scripts.
# Sourced, not executed. Defines constants and functions used by genAndRun,
# runLoop, etc. No shebang on purpose.

# Directory this file lives in, so paths work regardless of caller's cwd.
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Path to the wgslsmith binary. Override from the command line to test a
# different build, e.g.  WGSLSMITH=/other/build/wgslsmith fuzz/genAndRun 8
WGSLSMITH="${WGSLSMITH:-/home/xyl25/mysmith/target/debug/wgslsmith}"

# Shader-translation / validation tools used by glslangCheck. Each is
# overridable from the environment the same way as WGSLSMITH above.
TINT="${TINT:-/home/xyl25/dawn/out/Debug/tint}"
NAGA="${NAGA:-/home/xyl25/mysmith/external/wgpu/target/debug/naga}"
GLSLANG="${GLSLANG:-/home/xyl25/dawn/out/glslang-sanitized/glslang}"

# Root for all generated, disposable run artifacts (gitignored).
RUNS_DIR="${RUNS_DIR:-$COMMON_DIR/../runs}"

# Strip ANSI escape codes from stdin so logs are plain text.
strip_ansi() {
    sed 's/\x1b\[[0-9;]*[a-zA-Z]//g'
}

# Source a named config file from configs/. Sets CONFIGS, TIMEOUT, GEN_FLAGS.
# Provides defaults first so a config only overrides what it cares about.
load_config() {
    local name="$1"
    CONFIGS=""        # value for `run -c` (empty = backend defaults: both)
    TIMEOUT=120       # per-execution timeout in seconds (0 disables)
    GEN_FLAGS=""      # extra flags passed to `gen`

    local file="$COMMON_DIR/configs/$name.env"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: no config '$name' (expected $file)" >&2
        return 1
    fi
    # shellcheck disable=SC1090
    source "$file"
}

# Echo the run directory for a config+seed, creating it.
run_dir() {
    local config="$1" seed="$2"
    local dir="$RUNS_DIR/$config/$seed"
    mkdir -p "$dir"
    echo "$dir"
}

# Short git hash of the wgslsmith build, for reproducibility. "unknown" if
# the binary isn't inside a git checkout.
tool_hash() {
    git -C "$(dirname "$WGSLSMITH")" rev-parse --short HEAD 2>/dev/null || echo unknown
}

# Best-effort failure category from log text on stdin. Specific signatures
# are checked before generic ones; "panic" is the catch-all since every
# failure ends in one.
categorize() {
    local out; out=$(cat)
    if   grep -q "Invalid explicit layout decorations" <<<"$out"; then echo "spirv-layout-decoration"
    elif grep -q "FoldVectors"                          <<<"$out"; then echo "swiftshader-fold-crash"
    elif grep -q "^timeout$"                            <<<"$out"; then echo "timeout"
    elif grep -q "ABORT: ASSERT"                        <<<"$out"; then echo "swiftshader-assert"
    elif grep -q "panicked"                             <<<"$out"; then echo "panic"
    else echo "other"; fi
}

# Append a one-line JSON record describing a run to both the per-seed
# meta.json and the per-config results.jsonl (for comparing configs).
# Args: seeddir config seed status category
write_meta() {
    local seeddir="$1" config="$2" seed="$3" status="$4" category="$5"
    local json
    json=$(printf '{"seed":%s,"config":"%s","configs":"%s","tool_hash":"%s","timeout":%s,"status":"%s","category":"%s","timestamp":"%s"}' \
        "$seed" "$config" "$CONFIGS" "$(tool_hash)" "$TIMEOUT" "$status" "$category" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
    echo "$json" > "$seeddir/meta.json"
    echo "$json" >> "$RUNS_DIR/$config/results.jsonl"
}

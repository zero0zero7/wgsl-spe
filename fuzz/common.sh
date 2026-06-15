# common.sh — shared helpers for the fuzzing scripts.
# Sourced, not executed. Defines constants and functions used by genAndRun,
# runLoop, etc. No shebang on purpose.

# Directory this file lives in, so paths work regardless of caller's cwd.
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Repository root and its Gradle wrapper, used by runners that drive JVM tools
# (e.g. skeletalRun -> printSkeletalPrograms). Gradle must be invoked from the
# repo root, hence the absolute path.
REPO_ROOT="$(cd "$COMMON_DIR/.." && pwd)"
GRADLEW="$REPO_ROOT/gradlew"

# Optional per-machine overrides (gitignored). Copy common.local.sh.example to
# common.local.sh and set any tool paths that aren't on your PATH. Sourced
# first so its assignments win the ${VAR:-...} fallbacks below.
# shellcheck disable=SC1091
[ -f "$COMMON_DIR/common.local.sh" ] && source "$COMMON_DIR/common.local.sh"

# External tools. Resolution order for each: an explicit environment variable
# (or common.local.sh) wins; otherwise we search PATH. No absolute defaults are
# baked in, so the repo carries no machine-specific paths. Tools are validated
# lazily by require_tool, so a script only fails if a tool it actually uses is
# missing.
#   WGSLSMITH  generator/reconditioner (wgslsmith)        - genAndRun, glslangCheck
#   TINT       Dawn's WGSL->GLSL translator (tint)        - glslangCheck
#   NAGA       wgpu's shader translator (naga CLI)        - glslangCheck
#   GLSLANG    sanitizer-built glslang validator          - glslangCheck
WGSLSMITH="${WGSLSMITH:-$(command -v wgslsmith || true)}"
TINT="${TINT:-$(command -v tint || true)}"
NAGA="${NAGA:-$(command -v naga || true)}"
GLSLANG="${GLSLANG:-$(command -v glslang || true)}"

# Fail with a clear, actionable message if a required tool is missing.
# Usage: require_tool VARNAME human-name
require_tool() {
    local var="$1" name="$2" val="${!1}"
    if [[ -z "$val" || ! -x "$val" ]]; then
        echo "ERROR: $name not found." >&2
        echo "  Put it on your PATH, or set \$$var (e.g. export $var=/path/to/$name)," >&2
        echo "  or add it to fuzz/common.local.sh (see fuzz/common.local.sh.example)." >&2
        return 1
    fi
}

# Root for all generated, disposable run artifacts (gitignored).
RUNS_DIR="${RUNS_DIR:-$COMMON_DIR/../runs}"

# Strip ANSI/terminal escape codes from stdin so logs are plain text.
# Covers CSI sequences -- including private-mode ones like ESC[?25l (cursor
# hide) and erase-line ESC[2K that gradle/wgslsmith emit -- plus OSC title
# sequences and any other lone escape byte.
strip_ansi() {
    sed -E 's/\x1b\[[0-9;?]*[ -/]*[@-~]//g; s/\x1b\][^\x07]*\x07//g; s/\x1b[@-_]//g'
}

# Rewrite a file in place with escape codes removed. No-op if it doesn't exist.
strip_ansi_file() {
    local f="$1"
    [[ -f "$f" ]] || return 0
    strip_ansi < "$f" > "$f.stripped" && mv "$f.stripped" "$f"
}

# Source a named config file from configs/. Sets CONFIGS, TIMEOUT, GEN_FLAGS.
# Provides defaults first so a config only overrides what it cares about.
load_config() {
    local name="$1"
    CONFIGS=""        # value for `run -c` (empty = backend defaults: both)
    TIMEOUT=120       # per-execution timeout in seconds (0 disables)
    GEN_FLAGS=""      # extra flags passed to `gen`
    SKELETONS="${SKELETONS:-8}"   # random skeletons per shader (skeletalRun only;
                                  # honors an env override, e.g. SKELETONS=20 ...)
    PARSE_TIMEOUT="${PARSE_TIMEOUT:-60000}"  # ms budget for the JVM tool to parse a
                                  # shader (skeletalRun); raw wgslsmith output is
                                  # often slow to parse, so this exceeds the 10s
                                  # tool default. Honors an env override.
    PRESERVE_FORMAT="${PRESERVE_FORMAT:-1}"  # 1 = emit skeletons by splicing into
                                  # the original text (keeps wgslsmith's dialect);
                                  # 0 = re-serialize via AstWriter (skeletalRun
                                  # only). Honors an env override.

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

# Standard argument handling for the per-seed runners (genAndRun, glslangCheck,
# skeletalRun), which all take "<seed> [config]". On -h/--help or a bad arg
# count, calls the caller-defined usage() and exits 0. Otherwise sets SEED and
# CONFIG, loads the config, and validates wgslsmith (used by all three).
# Callers needing extra tools (e.g. glslangCheck's tint/naga/glslang) call
# require_tool again afterwards. Invoke as: parse_seed_args "$@"
parse_seed_args() {
    if [[ $# -lt 1 || $# -gt 2 || "$1" == "-h" || "$1" == "--help" ]]; then
        usage
        exit 0
    fi
    SEED="$1"
    CONFIG="${2:-default}"
    load_config "$CONFIG" || exit 1
    require_tool WGSLSMITH wgslsmith || exit 1
}

# Generate a shader for $SEED into the file $1, sending stderr to the log $2.
# Echoes "gen OK -> <out>" on success. On failure: strip escape codes from the
# log, record an error/gen-failed meta record, and exit 1. Relies on the
# globals SEED, GEN_FLAGS, SEEDDIR, and CONFIG (all set by parse_seed_args and
# the caller's path setup before this is called).
do_gen() {
    local out="$1" log="$2"
    if ! "$WGSLSMITH" gen $GEN_FLAGS "$SEED" > "$out" 2>"$log"; then
        strip_ansi_file "$log"
        echo "ERROR: gen failed (seed=$SEED)" >&2
        write_meta "$SEEDDIR" "$CONFIG" "$SEED" "error" "gen-failed"
        exit 1
    fi
    echo "gen OK -> $out"
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

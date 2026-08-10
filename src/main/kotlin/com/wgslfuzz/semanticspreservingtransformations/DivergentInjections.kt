package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Type

// Injects into @compute entry points pairs of statements of the form:
//   if (<divergent condition>) { <target> = <perturb(target)>; }
//   if (<divergent condition>) { <target> = <restore(target)>; }
// , where <divergent condition> is derived from local_invocation_id
// , where <target> is a synthesized scalar variable (v0, v1) or an existing local variable (v2).
// Because both `if`s evaluate the same way for a given invocation, each invocation either takes both branches or neither,
// so <target> is ultimately unchanged -- the transformation is semantics preserving.
//
// Differs from the other transformations in this package in one important way:
// - existing opaque conditions (see KnownValueExpressions.kt) are derived from uniform buffer values,
//    so every invocation in a dispatch takes the same branch -- no divergence.
// - Our condition is invocation-varying by construction (depends on lid), so different invocations genuinely
//   diverge, exercising compiler/backend code paths for non-uniform control flow.
//   Our tool is focused on creating a mixture of uniform and divergent control and data flow, so this is a useful stress test.
//
// v0 (DivergentCounterInjection.kt):
// - Every exit from the entry point writes the counter into the first scalar of the shader's
//   output buffer.
// - Whichever thread performs the final overwrite, the value should be COUNTER_INITIAL_VALUE,
//   since every invocation perturbs and restores the counter the same number of times.
//   [eg. thread 60 perturb 3 times, and restores 3 times; thread 9 perturb 1 time and restores 1 time.]
// - The oracle checks that the output buffer's 0th scalar is COUNTER_INITIAL_VALUE. Other values
//   in the buffer are not compared across shader variants, because a racing multi-thread dispatch
//   makes them non-deterministic.
//
// v1 (DivergentCounterInjection.kt): similar to v0 except
// - Only the thread selected by the injected input buffer runs; all others return immediately.
// - Deterministic, so the oracle can compare the entire output buffer across variants rather than just the 0th scalar.
// - The counter lives in its own dedicated output buffer.
//
// v2 (DivergentLocalInjection.kt): similar to v1 except
// - Instead of a synthesized counter, an EXISTING local variable already used by the entry point
//   is hijacked as the target: it must be a function-scope `var`, reduced to a scalar leaf via
//   firstScalarLeaf() if it is not already scalar.
// - Perturb and restore are no longer adjacent: real code can sit between them. Both are still
//   gated by conditions that agree per-thread -- unlike v0/v1's throwaway counter, this variable
//   is real program state the shader still reads, so an unmatched branch would corrupt it rather
//   than merely skew a diagnostic.
// - The declaration and the use need not share a scope: a `var` declared in an outer scope (eg. a
//   function body) may be perturbed inside an inner scope (eg. a `for` loop body). Which scope is
//   chosen, and where within it the two statements land, is randomized.
//
// Every @compute entry point is instrumented:
// - Limited to entry points because only they have direct access to local_invocation_id
// - If local_invocation_id is not already among its parameters (directly, or via a struct
//   parameter), a fresh parameter (`@builtin(local_invocation_id) divergent_lid_<id>: vec3<u32>`)
//   is appended to the entry point's parameter list. A struct parameter is only ever read from,
//   never synthesized: WGSL forbids the same builtin appearing twice across an entry point's
//   parameters, so an existing one (whatever its shape) has to be reused rather than duplicated.
// - An entry point where no candidate site happened to be selected is left completely untouched --
//   no counter, and in particular no write clobbering the output buffer for no reason.
//
// Scope limitation: only applies within an entry point's own body. It does not thread the counter
// or local_invocation_id into callees, so functions called from the entry point are untouched.
//
// Known reduction limitation: the injected counter `Statement.Variable` and the synthesized lid
// `ParameterDecl` carry AddedIdentifier, which is not an AugmentedMetadata, and
// RemoveAddedIdentifiers only removes GlobalDecl.Function -- so they are never reduced away. The
// perturb/restore pairs themselves are reducible, atomically, via DeletableStatement.

// Guards the first-scalar walk against a pathological chain of nested aggregates
// (and against a struct that somehow refers to itself, which would otherwise not terminate).
internal const val MAX_OUTPUT_NESTING_DEPTH = 16

// Value the injected counter is initialised to, and -- since every perturbation is matched by a
// restore -- the value it must still hold when the entry point exits.
// Not 0, so that the expected end state is distinct from a zero buffer produced by accident.
// The divergence oracle asserts this exact value; keep fuzz/lib/divergenceCheck.sh in step.
// No particular significance to 993, just a distinctive non-zero constant.
// Also, no point in making this user-configurable.
internal const val COUNTER_INITIAL_VALUE = 993

// The single i32 member shared by v1's injected input (thread selector) and output (counter) structs.
// Read `thread_to_run.<member>` and write `counter_output.<member>`.
internal const val V1_STRUCT_MEMBER = "data"

// v2's injected input buffer uses the same struct, and so the same member name.
internal const val V2_STRUCT_MEMBER = V1_STRUCT_MEMBER

// Constants parked in the injected input buffer alongside the thread selector, so that the shader
// reads them at runtime and no compiler can constant-fold an expression built from them.
//
// NOT a route from uniform to divergent: a `var<storage, read>` load is uniform under
// WGSL's uniformity analysis, identical in every invocation. 
// Only local_invocation_id makes an expression invocation-varying.
internal val HIDDEN_CONSTANT_MEMBERS: List<Pair<String, HiddenScalar>> =
    listOf("i32", "u32", "f32").flatMap { suffix ->
        listOf("zero", "one", "min", "max").map { kind ->
            "${kind}_$suffix" to HiddenScalar(kind, suffix)
        }
    }

/** Identifies one member of the hidden-constant block: eg. ("min", "i32") -> `min_i32`. */
internal data class HiddenScalar(
    val kind: String,
    val suffix: String,
) {
    val memberName: String get() = "${kind}_$suffix"
}

/** The hidden-constant member holding [kind] for [type], or null for a type with no entry (f16). */
internal fun hiddenMemberFor(
    kind: String,
    type: Type.Scalar,
): String? =
    when (type) {
        Type.I32 -> "${kind}_i32"
        Type.U32 -> "${kind}_u32"
        Type.F32 -> "${kind}_f32"
        else -> null
    }

fun addDivergentInjectionsV0(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob = applyV0(shaderJob, fuzzerSettings)

fun addDivergentInjectionsV1(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob = applyV1(shaderJob, fuzzerSettings)

// Returns null if no injection was performed, so that the caller can skip the shader variant and avoid a false positive in the divergence oracle.
fun addDivergentInjectionsV2(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob? = applyV2(shaderJob, fuzzerSettings)

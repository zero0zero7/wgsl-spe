package com.wgslspe.core

import com.wgslfuzz.core.*

/**
 * A function-call site that is a candidate for callee replacement,
 *  paired with callee's signature, scope available at call site, and function whose body contains the call.
 *
 * [call] is either an [Expression.FunctionCall] or a [Statement.FunctionCall]. 
 * Only calls to user-defined functions (those with a [ScopeEntry.Function] in scope) are candidates; 
 *  builtin calls have a separate  class.
 */
data class CallCandidate(
    val call: AstNode,
    val calleeType: FunctionType,
    val scope: Scope,
    val enclosingFunction: GlobalDecl.Function,
    val isStatementCall: Boolean,
)

/**
 * A builtin-call site whose callee belongs to a group in [builtinSwapGroups],
 *  paired with the scope available at the call (used to reject names shadowed by user declarations) (wgsl does not reserve builtin names). 
 * Unlike [CallCandidate], no signature or enclosing function is needed: group membership already guarantees signature compatibility, and builtins cannot participate in call-graph cycles.
 */
data class BuiltinCallCandidate(
    val call: Expression.FunctionCall,
    val callee: String,
    val scope: Scope,
    val constArgs: Boolean, // all arguments are const-expressions (spec 8.1.1), so the call itself is const-evaluated
)

/**
 * Groups of builtin functions that are freely interchangeable at any call site: 
 *  members of a group have identical overload sets in the WGSL specification and validity metadata (all are `@const` and `@must_use`, with no shader-stage or uniformity restrictions) -- so no signature machinery is needed.
 *
 * Deliberately excluded: abs/sign (integer domains differ), mix (extra vector-scalar overload), clamp (integer overloads, unlike smoothstep/fma), normalize/length/dot/cross (vector-only or shape-changing), quantizeToF16 (f32-only), dpdx-family (fragment-only), barriers and atomics (stage/address-space restrictions). 
 * These builtins do not fit into the main groups defined below, could add addition 1-way swap rules in future.
 *
 * Some built-ins define domains, beyond which (out-of-domain) results are indeterminate (e.g. acosh below 1, smoothstep with equal edges). [unsafeAsReplacement] makes swaps directional: those members can be replaced, but are never swapped in, so no variant acquires indeterminate behaviour.
 **/
private val builtinSwapGroups: List<Set<String>> =
    // All are @const (can occur outside function bodies), @must_use (only occur as experessions)
    listOf(
        // (T) -> T, T = f32/f16 scalar or vector.
        setOf(
            "sin", "cos", "tan", "asin", "acos", "atan",
            "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
            "exp", "exp2", "log", "log2",
            "sqrt", "inverseSqrt",
            "ceil", "floor", "fract", "trunc", "round",
            "degrees", "radians", "saturate",
        ),
        // (T, T) -> T, T = f32/f16/abstractFloat scalar or vector.
        setOf("atan2", "pow", "step"),
        // Ternary float, elementwise: (T, T, T) -> T, T = f32/f16/abstractFloat scalar or vector.
        setOf("fma", "smoothstep"),
        // Binary numeric: (T, T) -> T, , T = i32/i16/abstractInt/f32/f16/abstractFloat scalar or vector.
        setOf("min", "max"),
        // Unary integer, elementwise: (T) -> T, T = i32/u32 scalar or vector.
        setOf(
            "countLeadingZeros", "countOneBits", "countTrailingZeros",
            "firstLeadingBit", "firstTrailingBit", "reverseBits",
        ),
    )

private val builtinSwapGroup: Map<String, Set<String>> =
    builtinSwapGroups.flatMap { group -> group.map { it to group } }.toMap() // map every builtin (String) in each group to the group (Set<String>)

/**
 * Group members whose result is indeterminate on part of their input domain (per the WGSL spec):
 *  asin/acos outside [-1, 1], acosh below 1, atanh outside (-1, 1), sqrt below 0, log/log2/inverseSqrt at or below 0,
 *  pow for a negative base and atan2 at (0, 0) (both defined via log2/division), smoothstep with equal edges.
 * Swaps involving them are directional: a partial-domain callee may be replaced (the remaining group members are total, hence defined wherever it was), but is never swapped in, so no variant becomes indeterminate on inputs where the original was defined.
 */
private val unsafeAsReplacement: Set<String> =
    setOf("asin", "acos", "acosh", "atanh", "sqrt", "log", "log2", "inverseSqrt", "pow", "atan2", "smoothstep")

/**
 * Total-domain group members whose result magnitude can exceed the f32 range for representable
 * arguments (sinh/cosh/exp overflow beyond ~±88, exp2 beyond 128, tan near odd multiples of pi/2,
 * degrees by scaling ~57x). Swapping one in at a call site whose arguments are const-expressions
 * makes the call itself an evaluated const-expression (spec 8.1.1), and const-eval overflow is a
 * mandatory shader-creation error (spec 15.7.2) -- so such swaps are suppressed at const-argument
 * sites only. At runtime-argument sites they stay available: runtime overflow is an indeterminate
 * value, the same exposure the base generator already accepts.
 */
private val overflowProneAsReplacement: Set<String> =
    setOf("sinh", "cosh", "exp", "exp2", "tan", "degrees")

/**
 * Const-expression check per WGSL spec 8.1.1: an expression is a const-expression iff every
 * identifier in it resolves to a const-declaration or a const-function. User-defined functions are
 * never `@const`, so a call resolving to [ScopeEntry.Function] is disqualifying; an unresolved
 * callee is a builtin and over-approximated as `@const`. Over-approximation errs toward "const",
 * which only suppresses replacement candidates -- it never admits an invalid variant.
 */
private fun isConstExpression(
    expr: Expression,
    scope: Scope,
): Boolean =
    nodesPreOrder(expr).all { node ->
        when (node) {
            is Expression.Identifier ->
                when (val entry = scope.getEntry(node.name)) {
                    is ScopeEntry.GlobalConstant -> true
                    is ScopeEntry.LocalValue -> entry.astNode.isConst // `const` local yes, `let` no
                    null -> true // no user declaration: a builtin name
                    else -> false // parameter, var, override: runtime/override expression
                }
            is Expression.FunctionCall -> scope.getEntry(node.callee) !is ScopeEntry.Function
            else -> true
        }
    }

/**
 * Returns all replaceable call sites in [tu]: user-defined function calls as [CallCandidate]s, and builtin calls whose callee belongs to a swap group as [BuiltinCallCandidate]s.
 */
fun collectCallCandidates(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): Pair<List<CallCandidate>, List<BuiltinCallCandidate>> {
    val candidates = mutableListOf<CallCandidate>()
    val builtinCandidates = mutableListOf<BuiltinCallCandidate>()

    fun walk(
        node: AstNode,
        enclosingFunction: GlobalDecl.Function?,
        enclosingStatement: Statement?,
    ) {
        val currentFunction = if (node is GlobalDecl.Function) node else enclosingFunction
        val currentStatement = if (node is Statement) node else enclosingStatement

        // A call with a template parameter (e.g. bitcast<T>) is necessarily a builtin.
        val callee: String? =
            when (node) {
                is Expression.FunctionCall -> if (node.templateParameter == null) node.callee else null
                is Statement.FunctionCall -> node.callee
                else -> null
            }
        if (callee != null) {
            val scope = currentStatement?.let { env.scopeAvailableBefore(it) } ?: env.globalScope
            val entry = scope.getEntry(callee)
            if (entry is ScopeEntry.Function) {
                // User-defined functions cannot be called outside function bodies (WGSL has no user-defined const-functions), requires enclosing function
                if (currentFunction != null) {
                    candidates.add(
                        CallCandidate(node, entry.type, scope, currentFunction, node is Statement.FunctionCall),
                    )
                }
            } else if (entry == null && node is Expression.FunctionCall && callee in builtinSwapGroup) {
                // A callee not in scope is a builtin call
                builtinCandidates.add(
                    BuiltinCallCandidate(node, callee, scope, node.args.all { isConstExpression(it, scope) }),
                )
            }
        }

        traverse({ child, _ -> walk(child, currentFunction, currentStatement) }, node, Unit)
    }

    walk(tu, null, null)
    return Pair(candidates, builtinCandidates)
}

/**
 * Assigns each function in [tu] an index forming a topological order of the acyclic (recursion is forbidden) directed call graph:
 * every callee's index is strictly less than its callers' indices.
 * 
 * Note: scope alone gives no such guarantee. WGSL allows out-of-order function calls, and the resolver brings every function header into scope before resolving any body, so even the enclosing function itself is "in scope" at the call site;
 *
 * Callee edges are over-approximated by name (a callee string matching a function name counts
 * as an edge even without resolving it), which is safe: extra edges only make the replacement
 * filter more conservative.
 */
private fun callGraphTopoIndex(tu: TranslationUnit): Map<String, Int> {
    val functions = tu.globalDecls.filterIsInstance<GlobalDecl.Function>()
    val functionNames = functions.map { it.name }.toSet()
    // Create a map of user-defined function and the other user-defined functions it calls directly (non-recursive, single-layer)
    val callees: Map<String, Set<String>> =
        functions.associate { fn ->
            fn.name to
                nodesPreOrder(fn.body)
                    .mapNotNull { node ->
                        when (node) {
                            is Expression.FunctionCall -> node.callee
                            is Statement.FunctionCall -> node.callee
                            else -> null
                        }
                    }.filter { it in functionNames }
                    .toSet()
        }

    val index = mutableMapOf<String, Int>()
    var next = 0
    var remaining = functions.map { it.name }
    while (remaining.isNotEmpty()) {
        // Partitions the remaining functions into those whose callees are all already indexed (ready) and those with at least one callee not yet indexed (notReady). If ready is empty, there is a cycle in the call graph.
        val (ready, notReady) = remaining.partition { name -> callees.getValue(name).all { it in index } }
        if (ready.isEmpty()) {
            throw IllegalStateException("Call graph contains a cycle; input is not valid WGSL")
        }
        // Process in declaration order within each round so the indexing is deterministic.
        ready.forEach { index[it] = next++ }
        remaining = notReady
    }
    return index
}

/**
 * Returns names of all functions in scope that can replace user-defined [candidate]'s callee, including original callee (so return list is never empty).
 *
 * A replacement must:
 *  - have exactly the same [FunctionType] (resolved parameter and return types);
 *  - not be a shader entry point (entry points cannot be called from WGSL code);
 *  - sit strictly earlier than the enclosing function in [topoIndex]. 
 *  - not be `@must_use` when the call is a bare statement call, since a value-returning `@must_use` function cannot have its result discarded.
 */
private fun suitableFunctions(
    candidate: CallCandidate,
    topoIndex: Map<String, Int>,
): List<String> {
    val callerIndex = topoIndex.getValue(candidate.enclosingFunction.name)
    return candidate.scope
        .getAllEntries()
        .filterIsInstance<ScopeEntry.Function>()
        .filter { it.type == candidate.calleeType }
        .filter { entry ->
            entry.astNode.attributes.none {
                it is Attribute.Compute || it is Attribute.Vertex || it is Attribute.Fragment
            }
        } // exclude shader entry points
        .filter { entry -> topoIndex[entry.astNode.name]?.let { it < callerIndex } ?: false }
        .filter { entry ->
            !candidate.isStatementCall || entry.astNode.attributes.none { it is Attribute.MustUse }
        }
        .map { it.astNode.name }
}

/**
 * Returns names of all builtins that can replace builtin [candidate]'s callee:
 *  the members of its group, minus [unsafeAsReplacement] members,
 *  minus [overflowProneAsReplacement] members when the call's arguments are const-expressions
 * (the swapped-in call would be const-evaluated and an overflow is a shader-creation error),
 *  minus any name that a user declaration shadows at the call site (a call to a shadowed
 * name would resolve to the user declaration instead of the builtin).
 * The original callee always remains an option, even if partial-domain or shadowed.
 */
private fun suitableBuiltins(candidate: BuiltinCallCandidate): List<String> =
    builtinSwapGroup
        .getValue(candidate.callee)
        .filter { it == candidate.callee || it !in unsafeAsReplacement } // identity or total-domain
        .filter { it == candidate.callee || !(candidate.constArgs && it in overflowProneAsReplacement) } // no const-eval overflow
        .filter { it == candidate.callee || candidate.scope.getEntry(it) == null } // identity or not shadowed

// List of call sites; Each call site has a list of pairs <original call node, replacement callee name>.
// User-defined call sites followed by builtin call sites (both in traversal order).
// internal: also consumed by CombinedEnumerator.
internal fun functionCallChoices(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
): List<List<Pair<AstNode, String>>> {
    val topoIndex = callGraphTopoIndex(tu)
    val (userCalls, builtinCalls) = collectCallCandidates(tu, env)
    val userChoices =
        userCalls.map { candidate -> suitableFunctions(candidate, topoIndex).map { name -> candidate.call to name } }
    val builtinChoices =
        builtinCalls.map { candidate -> suitableBuiltins(candidate).map { name -> candidate.call as AstNode to name } }
    return (userChoices + builtinChoices).filter { it.isNotEmpty() } // notEmpty filter as safeguard, by right wouldnt trigger
}

/**
 * Clones [tu], replacing the callee of each call node in [newCallees] with its mapped name.
 *
 * Unlike variable usages, call nodes are not leaves: one replacement can be nested in another replacement's arguments. A plain `tu.clone { replacementMap[it] }` would drop nested replacements. Instead the replacement node is built with a recursive callback
 *
 * [leafReplacements] (used by CombinedEnumerator) additionally swaps leaf nodes — variable usages —
 * in the same clone pass; the two node sets are disjoint, and the recursive argument cloning
 * handles variable replacements nested inside replaced calls.
 */
internal fun applyCalleeReplacements(
    tu: TranslationUnit,
    newCallees: Map<AstNode, String>,
    leafReplacements: Map<AstNode, AstNode> = emptyMap(),
): TranslationUnit {
    fun replace(node: AstNode): AstNode? {
        leafReplacements[node]?.let { return it }
        val newName = newCallees[node] ?: return null
        // The parsed SourceSpan covers the *original* callee token; it no longer describes the renamed node, so drop it.
        fun withoutSpan(metadata: Set<Metadata>) = metadata.filterNot { it is SourceSpan }.toSet()
        return when (node) {
            is Expression.FunctionCall ->
                Expression.FunctionCall(newName, node.templateParameter?.clone(), node.args.map { it.clone(::replace) }, withoutSpan(node.metadata))
            is Statement.FunctionCall ->
                Statement.FunctionCall(newName, node.args.map { it.clone(::replace) }, withoutSpan(node.metadata))
            else -> null
        }
    }
    return tu.clone(::replace)
}

/**
 * Lazily enumerates skeletal variants of [tu] produced by simultaneously replacing the callee of every replaceable call site:
 *  user-defined callees are swapped for functions of identical signature,
 *  builtin callees for members of their swap group. 
 * 
 * Counterpart of [getVariableSkeletons] for function calls; each result pairs the rewritten [TranslationUnit] with the characteristic vector of chosen callee names (one per call site, in collection order: user-defined sites first, then builtin sites).
 */
fun getFunctionSkeletons(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<TranslationUnit, List<String>>> =
    functionCallCombinations(tu, env, n, random).map { (combination, charVect) ->
        Pair(applyCalleeReplacements(tu, combination.toMap()), charVect)
    }

/**
 * Like [getFunctionSkeletons], but yields the list of *edits* (original call node, replacement
 * callee name) instead of a re-serialized [TranslationUnit], mirroring [getVariableSkeletonEdits].
 */
fun getFunctionSkeletonEdits(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int = Int.MAX_VALUE,
    random: Boolean = true,
): Sequence<Pair<List<Pair<AstNode, String>>, List<String>>> = functionCallCombinations(tu, env, n, random)

/**
 * Shared driver for both the enumerate and random entry points: computes the per-site choices and enumerates (or randomly samples) distinct combinations, capped at the number that exist.
 * The characteristic vector is derived here, once, so the producers below carry a single representation of each combination.
 * Returns a sequence of all combis:
 * Each combi is a pair of <all replacements in that combi (rep as a pair of replacement site [AstNode], candidate name [String]), char vect>
 */ 
private fun functionCallCombinations(
    tu: TranslationUnit,
    env: ResolvedEnvironment,
    n: Int,
    random: Boolean = true,
): Sequence<Pair<List<Pair<AstNode, String>>, List<String>>> {
    val choices = functionCallChoices(tu, env)
    if (choices.isEmpty()) return emptySequence()

    val maxDistinct = choices.fold(1L) { accumulator, chc -> minOf(accumulator * chc.size, Int.MAX_VALUE.toLong()) }.toInt()
    println("Total call-replacement combinations (capped at Int.MAX_VALUE): $maxDistinct")

    fun withCharVect(combination: List<Pair<AstNode, String>>) = Pair(combination, combination.map { it.second })

    return if (random) {
        generateSequence { withCharVect(randomCallCombination(choices)) }
            .distinctBy { it.second }
            .take(minOf(n, maxDistinct))
    } else {
        enumerateCallCombinations(choices).map(::withCharVect).take(n)
    }
}

/**
 * Yields every combination of one replacement per call site.
 */
internal fun enumerateCallCombinations(
    choices: List<List<Pair<AstNode, String>>>,
): Sequence<List<Pair<AstNode, String>>> {
    fun enumerateFrom(
        callIdx: Int,
        combi: List<Pair<AstNode, String>>,
    ): Sequence<List<Pair<AstNode, String>>> = sequence {
        if (callIdx == choices.size) {
            yield(combi)
            return@sequence
        }
        for (choice in choices[callIdx]) {
            yieldAll(enumerateFrom(callIdx + 1, combi + choice))
        }
    }
    return enumerateFrom(0, emptyList())
}

/**
 * Picks one random replacement per call site.
 */
internal fun randomCallCombination(choices: List<List<Pair<AstNode, String>>>): List<Pair<AstNode, String>> =
    choices.map { it.random() } // iterate over outer list (the replacement site), chooses a random pair of <replacement site [AstNode], replacement candidate [String]> for each

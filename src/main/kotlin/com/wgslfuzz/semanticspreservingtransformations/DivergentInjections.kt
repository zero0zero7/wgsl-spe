package com.wgslfuzz.semanticspreservingtransformations

import kotlin.random.Random

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddedIdentifier
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.AssignmentOperator
import com.wgslfuzz.core.AstNode
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AugmentedMetadata
import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.BuiltinValue
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.ParameterDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.StructMember
import com.wgslfuzz.core.TranslationUnit
import com.wgslfuzz.core.TypeDecl
import com.wgslfuzz.core.clone
import com.wgslfuzz.core.nodesPreOrder
import com.wgslfuzz.core.traverse

import com.wgslfuzz.semanticspreservingtransformations.DivergentHelpers.*
import com.wgslfuzz.semanticspreservingtransformations.DivergentCreators.*



// Injects into @compute entry points pairs of statements of the form:
//   if (<divergent condition>) { <target>++; }
//   if (<divergent condition>) { <target>--; }
// , where <divergent condition> is derived from local_invocation_id
// , where <target> can be a synthesized scalar variable (v0, v1) or an existing local variable (v2).
// Because both `if`s evaluate to the same result, each invocation either takes both branches or neither,
// so <target> is ultimately unchanged -- the transformation is semantics preserving.
//
// Differs from other transformation in this package in one important way:
// - existing opaque conditions (see KnownValueExpressions.kt) are derived from uniform buffer values,
//  so every invocation in a dispatch takes the same branch -- no divergence.
// - Our condition is invocation-varying by construction, so different invocations genuinely diverge,
// exercising compiler/backend code paths for non-uniform control flow
//
// Specifically local_invocation_id (not global_invocation_id/workgroup_id):
// - to be paired with an oracle that runs the same instrumented shader twice -- once under its original @workgroup_size and once with it bumped to some N > 1 (see fuzz/divergenceOracle).
// At @workgroup_size(1), local_invocation_id is always (0,0,0), so the injected condition is trivially uniform
//
// v0:
// - Every exit from the entry point writes it into the first scalar of the shader's output buffer.
// - Regardless which thread does the final overwrite of its counter value onto the 0th ele in the output buffer, it should be 999 since all threads increment and decrement the counter the same number of times. 
// - Oracle checks that the output buffer's 0th scalar is 999, and flags a failure if not. (Other values in the output buffer are not used for comparison across shader variants due to non-determinism arising from data races.)
//
// v1:
// - Only thread 60 allowed to run program, all other threads do early return immediately in entry point. 
// - Note: divergent conditions are hardcoded to be %2 and %3 (TODO)
// - Deterministic, so that the oracle can check the entire output buffer for equality across shader variants, not just the 0th scalar.
// - Counter value represented in 2nd output.
//
// v2: similar to v1 except
// - Instead of a synthesized counter variable, an EXISTING local variable already used by the entry
//   point is hijacked as the target: it must be a local (function-scope `var`), reduced to a, scalar
//   leaf via firstScalarLeaf() if it isn't already scalar, written, and later read.
// - Increment and Decrement are no longer adjacent: the increment goes right after the write, the
//   decrement right before the read, so real code can sit between them. Both are still gated by the
//   SAME divergent condition (mod2, unlike v1's mismatched mod2/mod3) -- unlike v1's throwaway
//   counter, this variable is real program state the shader still reads, so an unmatched branch
//   would corrupt it rather than just skew a diagnostic. The increment and decrement must live in
//   the same Statement.Compound as each other (see findLocalVariableCandidates/findReadIndex), and
//   no Break/Return/Discard may sit between them (Continue is fine), since that could strand an
//   increment without its paired decrement ever running.
// - The declaration and the read need not share a scope: a `var` declared in an outer scope (eg. a
//   function body) may be read inside an inner scope (eg. a `for` loop body). When they differ, the
//   pair is injected into any single Compound on the path between the two (inclusive) -- the
//   declaring scope itself (write anchored at the declaration) or a descendant scope of it (write
//   anchored at the top of that scope, since the variable already holds a value by the time control
//   reaches it). Which scope is chosen, and where exactly within it the increment/decrement land,
//   is randomized -- see findLocalVariableCandidates.
// - No early-return thread-selection gating: every invocation actually runs concurrently (restoring
//   genuine cross-invocation divergence, which v1's single-thread restriction gave up). To avoid the
//   resulting write-write race on the output, each invocation gets its own bounds-checked slot in a
//   fixed-size output array, indexed by local_invocation_index (see createInputOutputForV2 /
//   createIndexedCounterWrite). The write/decrement magnitude is read from a dedicated dynamic input
//   buffer (not a literal) so it can't be compile-time constant-folded, and the same value is used
//   for both increment and decrement so a thread taking the shared branch cancels out exactly.
//
// Every @compute entry point is instrumented:
// - if local_invocation_id isn't already among its parameters (directly, or via a struct parameter),
//  a fresh parameter (`@builtin(local_invocation_id) divergent_counters_lid_<id>: vec3<u32>`) is appended to the entry point's parameter list.
//  A struct parameter is only ever read from, never synthesized: WGSL forbids the same builtin appearing twice
//  across an entry point's parameters, so an existing one (whatever its shape) has to be reused rather than duplicated.
// An entry point where no candidate site happened to be selected is left completely untouched --
// no counter, and in particular no write clobbering the output buffer for no reason.
//
// Scope limitation: only applies within an entry point's own body. It does not thread the counter
// or local_invocation_id into callees, so functions called from the entry point are untouched.

private typealias DivergentCounterInjections = MutableMap<Statement.Compound, Set<Int>>
private data class NestingInfo(val curr: Statement.Compound, var nested: MutableList<NestingInfo>)

// Guards the first-scalar walk against a pathological chain of nested aggregates
// (and against a struct that somehow refers to itself, which would otherwise not terminate).
private const val MAX_OUTPUT_NESTING_DEPTH = 16

// The single i32 member shared by v1's injected input (thread selector) and output (counter) structs.
// Read `thread_to_run.<member>` and Write `counter_output.<member>`
private const val V1_STRUCT_MEMBER = "data"

// v2's injected input (magnitude) and output (per-thread array) structs share this member name.
private const val V2_STRUCT_MEMBER = "data"

// Fixed capacity of v2's output array, one slot per thread (indexed by local_invocation_index).
// 256 comfortably covers WebGPU's spec max invocations per workgroup, so the bounds check added
// around each write is a safety net rather than something a real dispatch would ever hit.
private const val V2_OUTPUT_ARRAY_SIZE = 256

private class AddDivergentInjections(
    private val shaderJob: ShaderJob,
    private val fuzzerSettings: FuzzerSettings,
) {
    private lateinit var lidExpr: Expression

    // The single counter local belonging to the entry point currently being instrumented. 
    // Set in apply() before that entry point's body is rewritten; every pair injected into it refers to this one name.
    private lateinit var counterName: String

    private lateinit var incrementValue: Expression
    private lateinit var decrementValue: Expression

    /**
     * Finds an expression that reads local_invocation_id from an EXISTING parameter (directly, or via a struct parameter).
     * Returns null if none is present yet.
     */
    private fun findExistingLID(function: GlobalDecl.Function): Expression? {
        for (parameter in function.parameters) {
            val hasLID = parameter.attributes.filterIsInstance<Attribute.Builtin>().any { it.name == BuiltinValue.LOCAL_INVOCATION_ID }
            if (hasLID) {
                return Expression.Identifier(parameter.name)
            }
        }
        for (parameter in function.parameters) {
            val structName = (parameter.typeDecl as? TypeDecl.NamedType)?.name ?: continue
            val structDecl =
                shaderJob.tu.globalDecls
                    .filterIsInstance<GlobalDecl.Struct>()
                    .firstOrNull { it.name == structName } ?: continue
            val member =
                structDecl.members.firstOrNull { member ->
                    member.attributes.filterIsInstance<Attribute.Builtin>().any { it.name == BuiltinValue.LOCAL_INVOCATION_ID }
                } ?: continue
            return Expression.MemberLookup(Expression.Identifier(parameter.name), member.name)
        }
        return null
    }

    /**
     * Walks [type] down to its first scalar leaf, extending [base] with the member/index lookups needed to name that leaf. 
     */
    private fun firstScalarLeaf(
        base: LhsExpression,
        type: TypeDecl,
        depth: Int,
    ): Pair<LhsExpression, TypeDecl.ScalarTypeDecl>? {
        if (depth > MAX_OUTPUT_NESTING_DEPTH) {
            return null
        }
        return when (type) {
            is TypeDecl.ScalarTypeDecl -> base to type
            is TypeDecl.VectorTypeDecl -> LhsExpression.IndexLookup(base, zeroIndex()) to type.elementType
            // A matrix indexes to a column vector first, so it takes two steps to reach a scalar.
            is TypeDecl.MatrixTypeDecl ->
                LhsExpression.IndexLookup(LhsExpression.IndexLookup(base, zeroIndex()), zeroIndex()) to type.elementType
            is TypeDecl.Array ->
                type.elementCount?.let {
                    firstScalarLeaf(LhsExpression.IndexLookup(base, zeroIndex()), type.elementType, depth + 1)
                }
            is TypeDecl.NamedType -> {
                val members =
                    shaderJob.tu.globalDecls
                        .filterIsInstance<GlobalDecl.Struct>()
                        .firstOrNull { it.name == type.name }
                        ?.members ?: return null
                members.firstNotNullOfOrNull { member ->
                    firstScalarLeaf(LhsExpression.MemberLookup(base, member.name), member.typeDecl, depth + 1)
                }
            }
            else -> null
        }
    }


    /**
     * For multi-threaded execution. v2
     */
    private fun createMultiOutputInstance(
        outputBinding: String,
        structName: String,
    ): GlobalDecl.Variable = GlobalDecl.Variable(
        attributes = listOf(
            Attribute.Group(Expression.IntLiteral("0")),
            Attribute.Binding(Expression.IntLiteral(outputBinding)),
        ),
        name = "multithread_output",
        addressSpace = AddressSpace.STORAGE,
        accessMode = AccessMode.READ_WRITE,
        typeDecl = TypeDecl.NamedType(structName),
        initializer = null,
        )

    /**
     * For v0 and v1.
     */
    private fun createDivergentCounterPair(): List<Statement> {
        val id = fuzzerSettings.getUniqueId() // both conditions share a single id, so that both deleted together
        val incrementIf =
            Statement.If(
                condition = modNCondition(n = 2),
                thenBranch =
                    Statement.Compound(
                        listOf(
                            Statement.Assignment(
                                lhsExpression = LhsExpression.Identifier(counterName),
                                assignmentOperator = AssignmentOperator.PLUS_EQUAL,
                                rhs = incrementValue.clone(),
                            ),
                        ),
                    ),
                metadata = setOf(AugmentedMetadata.DeletableStatement(id, "divergent counter increment")),
            )
        val decrementIf =
            Statement.If(
                condition = modNCondition(n = 3),
                thenBranch =
                    Statement.Compound(
                        listOf(
                            Statement.Assignment(
                                lhsExpression = LhsExpression.Identifier(counterName),
                                assignmentOperator = AssignmentOperator.MINUS_EQUAL,
                                rhs = decrementValue.clone(),
                            ),
                        ),
                    ),
                metadata = setOf(AugmentedMetadata.DeletableStatement(id, "divergent counter decrement")),
            )
        return listOf(incrementIf, decrementIf)
    }

    

    /**
     * `if (<indexExpr> < V2_OUTPUT_ARRAY_SIZEu) { <outputBufferName>.data[<indexExpr>] = i32(<target>); }`
     * Bounds-checked so a workgroup with more threads than [V2_OUTPUT_ARRAY_SIZE] just skips the write for its excess threads.
     * For V2
     */
    private fun createIndexedOutputWrite(
        outputBufferName: String,
        indexExpr: Expression,
        target: LhsExpression,
    ): Statement =
        Statement.If(
            condition =
                Expression.Binary(
                    operator = BinaryOperator.LESS_THAN,
                    lhs = indexExpr.clone(),
                    rhs = Expression.IntLiteral("${V2_OUTPUT_ARRAY_SIZE}u"),
                ),
            thenBranch =
                Statement.Compound(
                    listOf(
                        Statement.Assignment(
                            lhsExpression =
                                LhsExpression.IndexLookup(
                                    LhsExpression.MemberLookup(LhsExpression.Identifier(outputBufferName), V2_STRUCT_MEMBER),
                                    indexExpr.clone(),
                                ),
                            assignmentOperator = AssignmentOperator.EQUAL,
                            rhs = Expression.I32ValueConstructor(listOf(lhsExprToExpr(target))),
                        ),
                    ),
                ),
        )

    /**
     * The shader's output buffer: the module-scope `var<storage, read_write>`.
     * wgslsmith emits exactly one (`s_output`); if a shader somehow has several, the first is used, since that is also the one the harness dumps first.
     */
    private fun findExistingOutputBuffer(): GlobalDecl.Variable? =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Variable>()
            .firstOrNull { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ_WRITE }

    /**
     * `<existing_output>.<..first scalar..> = <scalarType>(<counter>);`
     * Returns null when the shader has no output buffer.
     * For v0.
     */
    private fun createCounterOverwrite(): Statement? {
        val outputBuffer = findExistingOutputBuffer() ?: return null
        val (lhs, scalarType) =
            firstScalarLeaf(LhsExpression.Identifier(outputBuffer.name), outputBuffer.typeDecl ?: return null, 0)
                ?: return null
        // The counter is i32; convert rather than assume, so that an output buffer starting with eg. mat3x3<f32> or u32 gets a well-typed store.
        val counter = Expression.Identifier(counterName)
        val rhs: Expression =
            when (scalarType) {
                is TypeDecl.I32 -> Expression.I32ValueConstructor(listOf(counter))
                is TypeDecl.U32 -> Expression.U32ValueConstructor(listOf(counter))
                is TypeDecl.F32 -> Expression.F32ValueConstructor(listOf(counter))
                is TypeDecl.F16 -> Expression.F16ValueConstructor(listOf(counter))
                is TypeDecl.Bool -> Expression.BoolValueConstructor(listOf(counter))
            }
        return Statement.Assignment(
            lhsExpression = lhs,
            assignmentOperator = AssignmentOperator.EQUAL,
            rhs = rhs,
        )
    }

    /**
     * `<counter_output>.data = i32(<counter>);`
     * Stores counter into v1's output struct. 
     * The buffer is the `DynamicData` struct from [createInputOutputForV1].
     */
    private fun createCounterWrite(outputBufferName: String): Statement? {
        val lhs = LhsExpression.MemberLookup(LhsExpression.Identifier(outputBufferName), V1_STRUCT_MEMBER)
        val counter = Expression.Identifier(counterName)
        return Statement.Assignment(
            lhsExpression = lhs,
            assignmentOperator = AssignmentOperator.EQUAL,
            rhs = Expression.I32ValueConstructor(listOf(counter)),
        )
    }



    // --- v2: local-variable candidate selection -------------------------------------------------


    /** Describes one selected local-variable target inside a single Compound.
     * [declIndex] is null when the injection scope is a descendant of the declaring scope
     */
    private data class LocalVariableTarget(
        val target: LhsExpression,
        val targetType: TypeDecl.ScalarTypeDecl,
        val declCompound: Statement.Compound,
        val declIndex: Int?, // declaration index within the compound
    )

    /** One `var` declaration: where it lives (scope + index) and the scalar lvalue/type v2 would target. */
    private data class Declaration(
        val name: String,
        val target: LhsExpression,
        val targetType: TypeDecl.ScalarTypeDecl,
        val scope: Statement.Compound,
        val index: Int,
    )

    /** Bundles the outputs of a single-statement, scope-stopping walk: see [collectDirectScopeInfo]. */
    private class DirectScopeInfo {
        val readNames = mutableSetOf<String>()
        val nestedCompounds = mutableListOf<Statement.Compound>()
    }

    /**
     * Collects, from [node] downward, every identifier read "in the current scope" -- ie. without crossing into a nested Statement.Compound
     * Collects every Compound reachable, so the caller knows which nested scopes to recurse into next. 
     */
    private fun collectDirectScopeInfo(
        node: AstNode,
        info: DirectScopeInfo,
    ) {
        when (node) {
            is Statement.Compound -> {
                info.nestedCompounds.add(node)
                return
            }
            is Expression.Identifier -> info.readNames.add(node.name)
            is Statement.Assignment ->
                if (node.assignmentOperator != AssignmentOperator.EQUAL) {
                    lhsBaseIdentifierName(node.lhsExpression)?.let { info.readNames.add(it) }
                }
            else -> {}
        }
        traverse(::collectDirectScopeInfo, node, info)
    }

    /**
     * For v2
     * Walks [body], treating every Statement.Compound as a lexical scope
     * Finds local `var` declarations that resolve to a scalar via firstScalarLeaf.
     * Returns a "linked list" of NestingInfo, by return the head ie. the statement.compound that represents the entrypoint function's body
     */
    private fun findLocalVariableCandidates(body: Statement.Compound):
            Pair<NestingInfo, List<LocalVariableTarget>> {
        fun walk(
            compound: Statement.Compound,
            declarations: MutableList<LocalVariableTarget>,
            currNest: NestingInfo
        ) {
            val statements = compound.statements

            for (index in statements.indices) {
                val statement = statements[index]
                val info = DirectScopeInfo()
                // for each statement in the compound, collect all identifiers read in that statement's scope, and all nested compounds to recurse into
                collectDirectScopeInfo(statement, info)
                // if this statement is a local variable declaration, and it resolves to a scalar, add it to the list of candidates
                if (statement is Statement.Variable) {
                    statement.typeDecl?.let { typeDecl ->
                        firstScalarLeaf(LhsExpression.Identifier(statement.name), typeDecl, 0)?.let { (target, targetType) ->
                            declarations.add(
                                LocalVariableTarget(
                                    declCompound = compound,
                                    target = target,
                                    targetType = targetType,
                                    declIndex = index,
                                ),
                            )
                        }
                    }
                }
                // stop walking the compound when encountering an exit, code that follows might or might not run, non-deterministic
                else if (isDisqualifyingExit(statement)) {
                    break
                }

                if (info.nestedCompounds.isNotEmpty()) {
                    for (comp in info.nestedCompounds) {
                        currNest.nested.add(NestingInfo(curr=comp, nested=mutableListOf<NestingInfo>()))
                        walk(comp, declarations, currNest)
                    }
                }
            }
        }

        val declarations = mutableListOf<LocalVariableTarget>()
        val nestingInfo = NestingInfo(curr=body, nested=mutableListOf<NestingInfo>())
        walk(body, declarations, nestingInfo)
        return nestingInfo to declarations
    }

    /**
     * 50% chance of selecting each candidate, but at least one is always selected
     */
    private fun selectLocalVariableTargets(candidates: List<LocalVariableTarget>): List<LocalVariableTarget> {
        require(candidates.isNotEmpty()) { "List of local variable targets must not be empty" }
        val filtered = candidates.filter { fuzzerSettings.injectDivergentCounter() }
        return filtered.ifEmpty {
            listOf(candidates.random())
        }
    }

    /**
     * For v0 and v1, selects a set of indices within each Compound where the increment/decrement pair should be injected. Random.
     */
    private fun selectInjectionPoints(
        node: AstNode,
        injections: DivergentCounterInjections,
    ) {
        traverse(::selectInjectionPoints, node, injections)
        if (node is Statement.Compound) {
            val range = 0..node.statements.size
            val filtered = range.filter { fuzzerSettings.injectDivergentCounter() }
            injections[node] = filtered.ifEmpty { listOf(range.random()) }.toSet()
        }
    }

    /**
     * For v0 and v1, injects the increment/decrement pair into every Compound at the indices selected by [selectInjectionPoints].
     */
    private fun injectDivergentInjections(
        node: AstNode,
        injections: DivergentCounterInjections,
        counterWrite: Statement?,
    ): AstNode? =
        injections[node]?.let { indices ->
            val compound = node as Statement.Compound
            val newBody = mutableListOf<Statement>()
            for (index in 0..compound.statements.size) {
                if (index in indices) {
                    newBody.addAll(createDivergentCounterPair())
                }
                if (index < compound.statements.size) {
                    val statement = compound.statements[index]
                    // Insert overwrite with counter before each `return` leaves the entry point
                    if (counterWrite != null && statement is Statement.Return) {
                        newBody.add(counterWrite.clone())
                    }
                    // Add original statements to newBody. Recursive to inject into nested (compound) statements
                    newBody.add(statement.clone { injectDivergentInjections(it, injections, counterWrite) })
                }
            }
            Statement.Compound(newBody, compound.metadata)
        }


    fun applyV0(): ShaderJob {
        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                // Only focus on compute functions -- Skip decl otherwise (simply returning it instead of using it)
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }

                val injections: DivergentCounterInjections = mutableMapOf()
                // Injections will store a mapping of each compound statement (decl.body) to a random set of indices for code injection
                selectInjectionPoints(decl.body, injections)
                
                // Return original decl if it has no injections within
                if (injections.values.none { it.isNotEmpty() }) {
                    assert(injections.isNotEmpty()) { "injections map should not be empty if any of its values are non-empty, unless this decl body has no statements in it" }
                    return@map decl
                }

                var parameters = decl.parameters
                val existing :Expression = findExistingLID(decl)
                lidExpr =
                    if (existing != null) {
                        existing
                    } else {
                        val (newParamDecl, paramExprIdentifier) = lidParameter(fuzzerSettings.getUniqueId())
                        parameters = parameters + newParamDecl
                        paramExprIdentifier // assigned to lidExpr
                    }

                counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}"
                val counterWrite = createCounterOverwrite()
                val injectedBody = decl.body.clone { injectDivergentInjections(it, injections, counterWrite) }

                val statements = mutableListOf<Statement>()
                statements.add(counterInstance(993, "injected_counter"))
                statements.addAll(injectedBody.statements)
                if (counterWrite != null && injectedBody.statements.lastOrNull() !is Statement.Return) {
                    statements.add(counterWrite.clone())
                }

                GlobalDecl.Function(
                    attributes = decl.attributes,
                    name = decl.name,
                    parameters = parameters,
                    returnAttributes = decl.returnAttributes,
                    returnType = decl.returnType,
                    body = Statement.Compound(statements, injectedBody.metadata),
                    metadata = decl.metadata,
                )
            }

        return ShaderJob(
            tu =TranslationUnit(
                    shaderJob.tu.directives,
                    newGlobalDecls,
                    shaderJob.tu.metadata,
                ),
            pipelineState = shaderJob.pipelineState,
        )
    }

    /**
     * Last-assigned @binding used by any module-scope variable in @group([group]), or null if the group binds nothing. 
     * Read straight off the AST attributes so it accounts for every buffer (including read_write outputs),
     *  not just those the pipeline state tracks values for ie. includes output buffers (ensure no buffer collision)
     */
    private fun lastBindingForGroup(group: Int): Int? =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Variable>()
            .filter { intAttribute(it, isGroup = true) == group }
            .mapNotNull { intAttribute(it, isGroup = false) }
            .maxOrNull()
    

    fun applyV1(): ShaderJob {
        val prevBinding = lastBindingForGroup(0) ?: -1
        val inputBinding = (prevBinding + 1).toString()
        val outputBinding = (prevBinding + 2).toString()

        val structDecl = dataStruct(fuzzerSettings.getUniqueId())
        val inputInstance = threadToRunInputInstance(inputBinding, structDecl.name)
        val outputInstance = scalarOutputInstance(outputBinding, structDecl.name, "counter_output")

        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }
                val injections: DivergentCounterInjections = mutableMapOf()
                selectInjectionPoints(decl.body, injections)
                if (injections.values.none { it.isNotEmpty() }) {
                    assert(injections.isNotEmpty()) { "injections map should not be empty if any of its values are non-empty, unless this decl body has no statements in it" }
                    return@map decl
                }
                var parameters = decl.parameters
                val existing :Expression = findExistingLID(decl)
                lidExpr =
                    if (existing != null) {
                        existing
                    } else {
                        val (newParameter, expr) = lidParameter(fuzzerSettings.getUniqueId())
                        parameters = parameters + newParameter
                        expr
                    }
                counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}"
                
                fun threadData(): Expression = Expression.MemberLookup(Expression.Identifier(inputInstance.name), V1_STRUCT_MEMBER)
                incrementValue = threadData()
                decrementValue = Expression.Binary(
                    operator = BinaryOperator.MODULO,
                    lhs = threadData(),
                    rhs = Expression.Binary(
                        operator = BinaryOperator.MINUS,
                        lhs = Expression.IntLiteral("2147483645i"),
                        rhs = threadData(),
                    ),
                )
                val counterWrite = createCounterWrite(outputBufferName=outputInstance.name)
                val injectedBody = decl.body.clone { injectDivergentInjections(it, injections, counterWrite) }

                val statements = mutableListOf<Statement>()
                statements.add(counterInstance(999, "injected_counter"))
                statements.addAll(injectedBody.statements)
                if (counterWrite != null && injectedBody.statements.lastOrNull() !is Statement.Return) {
                    statements.add(counterWrite.clone())
                }

                // if (i32(lid.x) != thread_to_run.data) { return; } -- only the selected thread runs.
                statements.add(0,
                    Statement.If(
                        condition = singleThreadCondition(lidExpr.clone(),
                            Expression.MemberLookup(Expression.Identifier(inputInstance.name), V1_STRUCT_MEMBER),
                            equals = false,
                        ),
                        thenBranch = Statement.Compound(statements=listOf(Statement.Return(null))),
                    )
                )

                GlobalDecl.Function(
                    attributes = decl.attributes,
                    name = decl.name,
                    parameters = parameters,
                    returnAttributes = decl.returnAttributes,
                    returnType = decl.returnType,
                    body = Statement.Compound(statements, injectedBody.metadata),
                    metadata = decl.metadata,
                )
            }

        return ShaderJob(
            tu =TranslationUnit(
                    shaderJob.tu.directives,
                    listOf(structDecl, inputInstance, outputInstance) + newGlobalDecls,
                    shaderJob.tu.metadata,
                ),
            pipelineState = shaderJob.pipelineState,
        )
    }

    /** Bundles what [injectLocalVariableCounters] needs to thread through the recursive clone. */
    private data class V2InjectionContext(
        val injectionsByCompound: Map<Statement.Compound, List<Pair<LocalVariableTarget, Int>>>,
        val magnitude: Expression,
        val outputBufferName: String,
        val indexExpr: Expression,
    )
    

    /**
     * v2: hijacks an existing local variable's write/read pair rather than injecting a fresh counter,
     * and lets every invocation actually run concurrently (no early-return gating) since each
     * invocation now owns its own output slot -- see [createInputOutputForV2] / [createIndexedCounterWrite].
     */
    fun applyV2(): ShaderJob {
        val prevBinding = lastBindingForGroup(0) ?: -1
        val inputBinding = (prevBinding + 1).toString()
        val outputBinding = (prevBinding + 2).toString()

        val inputStruct = dataStruct(fuzzerSettings.getUniqueId())
        val inputBuffer = threadToRunInputInstance(inputBinding, inputStruct.name)
        val outputStruct = multiOutputStruct(fuzzerSettings.getUniqueId(), 256)
        val outputBuffer = createMultiOutputInstance(outputBinding, outputStruct.name)

        val magnitude = Expression.MemberLookup(Expression.Identifier(inputBuffer.name), V2_STRUCT_MEMBER)

        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }

                // For each compute entrypoint, recursively find and randomly select local variables, noting their declaration compound and index, datatype
                val (nestingInfo, candidates)  = findLocalVariableCandidates(decl.body)
                val selected :List<LocalVariableTarget> = selectLocalVariableTargets(candidates)

                // Return original decl if no candidate was selected
                if (selected.isEmpty()) {
                    return@map decl
                }

                var parameters = decl.parameters
                val existingLid = findExistingLID(decl)
                lidExpr =
                    if (existingLid != null) {
                        existingLid
                    } else {
                        val (newParameter, expr) = lidParameter(fuzzerSettings.getUniqueId())
                        parameters = parameters + newParameter
                        expr
                    }

                // Map<CompoundStatement, List<Pair<Target, Id>>>
                // Groups targets by their declaration compound
                val injectionsByCompound =
                    selected
                        .map { it to fuzzerSettings.getUniqueId() }
                        .groupBy({ (target, _) -> target.declCompound }, { it })

                // For each compound, (starting from the outermost ie. the entrypoint function body)
                var stack = ArrayDequeue<NestingInfo>
                stack.addLast(nestingInfo)
                while (stack.isNotEmpty()) {
                    var currentNest = stack.pop()
                    var currentCompound = currentNest.currentCompound
                    for (nested in currentNest.nested) { stack.addLast(nested) }
                    var qualifiedTargets = injectionsByCompound[currentCompound]
                    // Select a random local variable as target
                    var (target, id) = qualifiedTargets.random()
                    if (target.declCompound == currentCompound) {
                        assert(target.declIndex != null)
                        if (target.declIndex != null) {
                            val index1 = random.nextInt(target.declIndex + 1, currentCompound.statements.size + 1)
                            val index2 = random.nextInt(target.declIndex + 1, currentCompound.statements.size + 1)
                        }
                    }
                    else {
                        val index1 = random.nextInt(0, currentCompound.statements.size+1)
                        val index2 = random.nextInt(0, currentCompound.statements.size+1)
                    }
                    val fixCondition = singleThreadCondition(lidExpr.clone(), Expression.MemberLookup(Expression.Identifier(inputBuffer.name), "data"), true)
                    val unfixCondition = singleThreadCondition(lidExpr.clone(), Expression.MemberLookup(Expression.Identifier(inputBuffer.name), "data"), true)
                    // Inject from bottom of compound
                    val unfixStatement = modificationStatement(unfixCondition, target.target, Expression.Binary(BinaryOperator.PLUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")), id)
                    val fixStatement = modificationStatement(fixCondition, target.target, Expression.Binary(BinaryOperator.MINUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")), id)
                    if (index1 >= index2) {
                        currentCompound.statements.add(index1, unfixStatement)
                        currentCompound.statements.add(index2, fixStatement)
                    }
                    else {
                        currentCompound.statements.add(index2, unfixStatement)
                        currentCompound.statements.add(index1, fixStatement)
                    }
                }

                GlobalDecl.Function(
                    attributes = decl.attributes,
                    name = decl.name,
                    parameters = parameters,
                    returnAttributes = decl.returnAttributes,
                    returnType = decl.returnType,
                    body = nestingInfo.currentCompound,
                    metadata = decl.metadata,
                )
            }

        return ShaderJob(
            tu =
                TranslationUnit(
                    shaderJob.tu.directives,
                    listOf(inputStruct, outputStruct, inputBuffer, outputBuffer) + newGlobalDecls,
                    shaderJob.tu.metadata,
                ),
            pipelineState = shaderJob.pipelineState,
        )
    }
}

fun addDivergentInjectionsV0(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentInjections(
        shaderJob,
        fuzzerSettings,
    ).applyV0()

fun addDivergentInjectionsV1(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentInjections(
        shaderJob,
        fuzzerSettings,
    ).applyV1()

fun addDivergentInjectionsV2(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentInjections(
        shaderJob,
        fuzzerSettings,
    ).applyV2()

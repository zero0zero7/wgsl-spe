package com.wgslfuzz.semanticspreservingtransformations

import kotlin.math.max
import kotlin.math.min

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
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.TypeDecl
import com.wgslfuzz.core.asStoreTypeIfReference
import com.wgslfuzz.core.clone
import com.wgslfuzz.core.nodesPreOrder
import com.wgslfuzz.core.toType
import com.wgslfuzz.core.traverse

import com.wgslfuzz.semanticspreservingtransformations.*



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
// - Regardless which thread does the final overwrite of its counter value onto the 0th ele in the output buffer, it should be COUNTER_INITIAL_VALUE ie. 993 since all threads increment and decrement the counter the same number of times. 
// - Oracle checks that the output buffer's 0th scalar is 993, and flags a failure if not. (Other values in the output buffer are not used for comparison across shader variants due to non-determinism arising from data races.)
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

internal typealias DivergentCounterInjections = MutableMap<Statement.Compound, Set<Int>>
internal data class NestingInfo(val curr: Statement.Compound, var nested: MutableList<NestingInfo>)

// Guards the first-scalar walk against a pathological chain of nested aggregates
// (and against a struct that somehow refers to itself, which would otherwise not terminate).
internal const val MAX_OUTPUT_NESTING_DEPTH = 16

// Value the injected counter is initialised to, and -- since every increment is matched by a
// decrement -- the value it must still hold when the entry point exits. 
// Not 0, so that the expected end state is distinct from a zero buffer produced by accident.
// The divergence oracle asserts this exact value; keep fuzz/lib/divergenceCheck.sh in step.
internal const val COUNTER_INITIAL_VALUE = 993

// The single i32 member shared by v1's injected input (thread selector) and output (counter) structs.
// Read `thread_to_run.<member>` and Write `counter_output.<member>`
internal const val V1_STRUCT_MEMBER = "data"

// v2's injected input (magnitude) and output (per-thread array) structs share this member name.
internal const val V2_STRUCT_MEMBER = "data"

// Fixed capacity of v2's output array, one slot per thread (indexed by local_invocation_index).
// 256 comfortably covers WebGPU's spec max invocations per workgroup, so the bounds check added
// around each write is a safety net rather than something a real dispatch would ever hit.
internal const val V2_OUTPUT_ARRAY_SIZE = 256

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

    /** Finds (or synthesizes) the local_invocation_id expression for [function],
     *  returning it alongside the (possibly extended) parameter list. */
    private fun getLidExpr(functionDecl: GlobalDecl.Function): Pair<Expression, List<ParameterDecl>> {
        val existing :Expression? = findExistingLID(functionDecl)
        if (existing != null) { return existing to functionDecl.parameters }
        // Create if not exist in original shader
        val (newParameter, expr) = lidParameter(fuzzerSettings.getUniqueId())
        return expr to (functionDecl.parameters + newParameter)
    }

    /**
     * Walks [type] down to its first scalar leaf, extending [base] with the member/index lookups needed to name that leaf. 
     */
    private fun firstScalarLeaf(
        base: LhsExpression,
        type: Type,
        depth: Int,
    ): Pair<LhsExpression, Type.Scalar>? {
        if (depth > MAX_OUTPUT_NESTING_DEPTH) {
            return null
        }
        return when (type) {
            // Reject Boolean and abstract types, since they can't be used as the target of an increment/decrement.
            Type.Bool, Type.AbstractInteger, Type.AbstractFloat -> null
            is Type.Scalar -> base to type
            is Type.Vector ->
                if (type.elementType == Type.Bool || type.elementType.isAbstract()) {
                    null
                } else {
                    LhsExpression.IndexLookup(base, zeroIndex()) to type.elementType
                }
            // A matrix indexes to a column vector first, so it takes two steps to reach a scalar.
            is Type.Matrix ->
                LhsExpression.IndexLookup(LhsExpression.IndexLookup(base, zeroIndex()), zeroIndex()) to type.elementType
            is Type.Array ->
                type.elementCount?.let {
                    firstScalarLeaf(LhsExpression.IndexLookup(base, zeroIndex()), type.elementType, depth + 1)
                }
            is Type.Struct -> {
                type.members.firstNotNullOfOrNull { (memberName, memberType) ->
                    firstScalarLeaf(LhsExpression.MemberLookup(base, memberName), memberType, depth + 1)
                }
            }
            else -> null
        }
    }

    /**
     * For v0 and v1.
     * Modifies on injected counter
     */
    private fun createDivergentCounterPair(lidExpr: Expression): List<Statement> {
        val id = fuzzerSettings.getUniqueId() // both conditions share a single id, so that both deleted together
        val incrementIf =
            Statement.If(
                condition = modNCondition(lidExpr.clone(), n = 2),
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
                condition = modNCondition(lidExpr.clone(), n = 3),
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
        val outputType =
            outputBuffer.typeDecl?.toType(shaderJob.environment.globalScope, shaderJob.environment)
                ?: return null
        val (lhs, scalarType) =
            firstScalarLeaf(LhsExpression.Identifier(outputBuffer.name), outputType, 0)
                ?: return null
        // The counter is i32; convert rather than assume, so that an output buffer starting with eg. mat3x3<f32> or u32 gets a well-typed store.
        val counter = Expression.Identifier(counterName)
        val rhs: Expression =
            when (scalarType) {
                Type.I32 -> Expression.I32ValueConstructor(listOf(counter))
                Type.U32 -> Expression.U32ValueConstructor(listOf(counter))
                Type.F32 -> Expression.F32ValueConstructor(listOf(counter))
                Type.F16 -> Expression.F16ValueConstructor(listOf(counter))
                Type.Bool -> Expression.BoolValueConstructor(listOf(counter))
                Type.AbstractInteger, Type.AbstractFloat -> return null
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
        val targetType: Type.Scalar,
        val declCompound: Statement.Compound,
        val declIndex: Int?, // declaration index within the compound
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
     * Finds all local `var` declarations that resolve to a scalar via firstScalarLeaf, including those in nested scopes.
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
                // For each statement in the compound, collect all *identifiers read* in that statement's scope, and all nested compounds to recurse into
                collectDirectScopeInfo(statement, info)
                // Collect local variable declaration, and add it to the list of candidates if it resolves to a scalar
                if (statement is Statement.Variable) {
                    val variableType =
                        // variable type explicitly declared
                        statement.typeDecl
                            ?.toType(shaderJob.environment.globalScope, shaderJob.environment)
                            // if not, infer from initializer
                            ?: statement.initializer
                                ?.let { shaderJob.environment.typeOf(it).asStoreTypeIfReference() }
                    // Extract numeric scalar leaf of variable using the type
                    variableType?.let { type ->
                        firstScalarLeaf(LhsExpression.Identifier(statement.name), type, 0)?.let { (target, targetType) ->
                            declarations.add( // Add to list of declarations found in current compound
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
                // Stop walking the compound when encountering an exit, code that follows might or might not run, non-deterministic
                else if (isDisqualifyingExit(statement)) {
                    break
                }
                // 
                if (info.nestedCompounds.isNotEmpty()) {
                    println("NUM OF NESTED: ${info.nestedCompounds.size}")
                    for (comp in info.nestedCompounds) {
                        currNest.nested.add(NestingInfo(curr=comp, nested=mutableListOf<NestingInfo>()))
                        walk(comp, declarations, currNest)
                    }
                }
                else {
                    println("never encounterd")
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
            listOf(fuzzerSettings.randomElement(candidates))
        }
    }

    /**
     * For v0 and v1
     * Given entry point's function body, traverse:
     * If is Statement.Compound, randomly select a set of indices within each Compound where the increment/decrement pair should be injected. 
     */
    private fun selectInjectionPoints(
        node: AstNode,
        injections: DivergentCounterInjections,
    ) {
        traverse(::selectInjectionPoints, node, injections)
        if (node is Statement.Compound) {
            val range = 0..node.statements.size
            val filtered = range.filter { fuzzerSettings.randomInt(100) < 30 } 
            // Fall back to a single random index if all were filtered out, so that at least one injection occurs in this compound
            injections[node] = filtered.ifEmpty { listOf(fuzzerSettings.randomElement(range.toList())) }.toSet()
        }
    }

    /**
     * For v0 and v1, injects the increment/decrement pair into every Compound at the indices selected by [selectInjectionPoints].
     */
    private fun injectDivergentInjections(
        lidExpr: Expression,
        node: AstNode,
        injections: DivergentCounterInjections,
        counterWrite: Statement?,
    ): AstNode? =
        injections[node]?.let { indices ->
            val compound = node as Statement.Compound
            val newBody = mutableListOf<Statement>()
            for (index in 0..compound.statements.size) {
                if (index in indices) {
                    newBody.addAll(createDivergentCounterPair(lidExpr))
                }
                if (index < compound.statements.size) {
                    val statement = compound.statements[index]
                    // Insert overwrite with counter before each `return` leaves the entry point
                    if (counterWrite != null && statement is Statement.Return) {
                        newBody.add(counterWrite.clone())
                    }
                    // Add original statements to newBody. Recursive to inject into nested (compound) statements
                    newBody.add(statement.clone { injectDivergentInjections(lidExpr, it, injections, counterWrite) })
                }
            }
            Statement.Compound(newBody, compound.metadata)
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

    /** The next two free @binding indices in @group(0) — used by v1/v2 for their extra buffers. */
    private fun nextTwoBindings(): Pair<String, String> {
        val prevBinding = lastBindingForGroup(0) ?: -1
        return (prevBinding + 1).toString() to (prevBinding + 2).toString()
    }
    

    /** Selects injection points for [body]; null means nothing was selected. */
    private fun computeInjectionsOrNull(body: Statement.Compound): DivergentCounterInjections? {
        val injections: DivergentCounterInjections = mutableMapOf()
        // Injections will store a mapping of each compound statement (decl.body) to a random set of indices for code injection
        selectInjectionPoints(body, injections)
        // Return injections if any of its values isnt empty, else return null
        return injections.takeIf { map -> map.values.any { it.isNotEmpty() } }
    }

    /** Applies [injections], then wraps with the initial counter decl and (if not already followed by a return) a trailing counter write.
     *  Shared by v0/v1. */
    private fun buildInjectedBody(
        body: Statement.Compound,
        injections: DivergentCounterInjections,
        lidExpr: Expression,
        counterWrite: Statement?,
        counterInitialValue: Int,
    ): Statement.Compound {
        val injectedBody = body.clone { injectDivergentInjections(lidExpr, it, injections, counterWrite) }
        val statements = mutableListOf<Statement>()
        statements.add(counterInstance(counterInitialValue, counterName))
        statements.addAll(injectedBody.statements)
        if (counterWrite != null && injectedBody.statements.lastOrNull() !is Statement.Return) {
            statements.add(counterWrite.clone())
        }
        return Statement.Compound(statements, injectedBody.metadata)
    }

    /** Re-wraps [newGlobalDecls] (optionally preceded by injected structs/buffers) into a ShaderJob. */
    private fun rebuildShaderJob(
        newGlobalDecls: List<GlobalDecl>,
        extraGlobalDecls: List<GlobalDecl> = emptyList(),
    ): ShaderJob =
        ShaderJob(
            tu = TranslationUnit(shaderJob.tu.directives, extraGlobalDecls + newGlobalDecls, shaderJob.tu.metadata),
            pipelineState = shaderJob.pipelineState,
        )

    /**
    * Inject counter, modify counter, overwrite first ele of original output with counter value
    * Oracle should only compare the first ele of the output, due to non-determinism with other variables
     */ 
    fun applyV0(): ShaderJob {
        val newGlobalDecls = mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val injections = computeInjectionsOrNull(decl.body) ?: return@mapComputeFunctions decl
            val (lidExpr, parameters) = getLidExpr(decl)
            counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}"
            val counterWrite = createCounterOverwrite()
            val randVal = fuzzerSettings.randomInt(1000)
            incrementValue = Expression.IntLiteral(randVal.toString() + "i")
            decrementValue = Expression.IntLiteral(randVal.toString() + "i")
            val body = buildInjectedBody(decl.body, injections, lidExpr, counterWrite, counterInitialValue = COUNTER_INITIAL_VALUE)
            decl.withParametersAndBody(parameters, body)
        }
        return rebuildShaderJob(newGlobalDecls)
    }

    /** 
    * Inject counter, modify counter, output counter
    * Only 1 thread actually runs -- determined by injected input
    */ 
    fun applyV1(): ShaderJob {
        val (inputBinding, outputBinding) = nextTwoBindings()
        val structDecl = dataStruct(fuzzerSettings.getUniqueId())
        val inputInstance = threadToRunInputInstance(inputBinding, structDecl.name)
        val outputInstance = scalarOutputInstance(outputBinding, structDecl.name, "counter_output")

        val newGlobalDecls = mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val injections = computeInjectionsOrNull(decl.body) ?: return@mapComputeFunctions decl

            val (lidExpr, parameters) = getLidExpr(decl)
            counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}"

            fun threadData() = Expression.MemberLookup(Expression.Identifier(inputInstance.name), V1_STRUCT_MEMBER)
            incrementValue = threadData()
            decrementValue = Expression.Binary(
                operator = BinaryOperator.MODULO,
                lhs = threadData(),
                rhs = Expression.Paren(target=Expression.Binary(BinaryOperator.MINUS, Expression.IntLiteral("2147483645i"), threadData())),
            )

            val counterWrite = createCounterWrite(outputBufferName = outputInstance.name)
            val body = buildInjectedBody(decl.body, injections, lidExpr, counterWrite, counterInitialValue = COUNTER_INITIAL_VALUE) // makes use of `incrementValue`, `decrementValue`

            val gatedBody = Statement.Compound(
                listOf(
                    Statement.If(
                        condition = singleThreadCondition(lidExpr.clone(), threadData(), equals = false),
                        thenBranch = Statement.Compound(listOf(Statement.Return(null))),
                    ),
                ) + body.statements,
                body.metadata,
            )

            decl.withParametersAndBody(parameters, gatedBody)
        }
        return rebuildShaderJob(newGlobalDecls, listOf(structDecl, inputInstance, outputInstance))
    }

    fun applyV2(): ShaderJob {
        val (inputBinding, outputBinding) = nextTwoBindings()
        val inputStruct = dataStruct(fuzzerSettings.getUniqueId())
        val inputBuffer = threadToRunInputInstance(inputBinding, inputStruct.name)
        var requireGate = true

        fun recursiveInjectLocalVariableModifiers(
            lidExpr: Expression,
            compound: Statement.Compound,
            injections: Map<Statement.Compound, List<Pair<LocalVariableTarget, Int>>>,
            ancestorTargets: List<Pair<LocalVariableTarget, Int>> = emptyList(),
        ): Statement.Compound {
            val currentTargets = injections[compound] ?: emptyList()
            val qualifiedTargets = ancestorTargets + currentTargets
            // Suitable targets = Targets in outer scope + Targets in current scope that appear before current statement
            fun targetsVisibleInside(statementIndex: Int): List<Pair<LocalVariableTarget, Int>> =
                ancestorTargets + currentTargets.filter { (target, _) ->
                    target.declIndex != null && target.declIndex < statementIndex
                }

            val newStatements = mutableListOf<Statement>()
            if (requireGate) {
                newStatements.add(0, 
                    Statement.If(
                        condition = singleThreadCondition(lidExpr.clone(), Expression.MemberLookup(Expression.Identifier(inputBuffer.name), "data"), false),
                        thenBranch = Statement.Compound(listOf(Statement.Return(null))),
                    )
                )
                requireGate = false
            }

            // ----- If current scope has no suitable declarations, traversal must continue although no target can safely be modified in this scope yet. -----
            // Continue recursing into nested scope (which might have suitable targets)
            // Return new cloned scope
            if (qualifiedTargets.isEmpty()) {
                compound.statements.forEachIndexed { statementIndex, statement ->
                    newStatements.add(
                        statement.clone { node ->
                            if (node is Statement.Compound) {
                                recursiveInjectLocalVariableModifiers(
                                    lidExpr,
                                    node,
                                    injections,
                                    targetsVisibleInside(statementIndex),
                                )
                            } else {
                                null
                            }
                        },
                    )
                }
                return Statement.Compound(newStatements, compound.metadata)
            }

            // ----- Else, when there are suitable modification targets -----
            // Pick a random target, pick 2 random indices, ensure that indices are after declIndex if target is declared in current scope
            val (target, id) = fuzzerSettings.randomElement(qualifiedTargets)
            // A target declared in this scope can only be modified after its declaration
            val lowestIndex =
                if (target.declCompound == compound) {
                    assert(target.declIndex != null)
                    (target.declIndex ?: -1) + 1
                } else {
                    0
                }
            val index1: Int = fuzzerSettings.randomInt(lowestIndex, compound.statements.size + 1)
            val index2: Int = fuzzerSettings.randomInt(lowestIndex, compound.statements.size + 1)

            val fixCondition = singleThreadCondition(lidExpr.clone(), Expression.MemberLookup(Expression.Identifier(inputBuffer.name), "data"), true)
            val unfixCondition = singleThreadCondition(lidExpr.clone(), Expression.MemberLookup(Expression.Identifier(inputBuffer.name), "data"), true)
            val unfixStatement = modificationStatement(unfixCondition, target.target, Expression.Binary(BinaryOperator.PLUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")), id)
            val fixStatement = modificationStatement(fixCondition, target.target, Expression.Binary(BinaryOperator.MINUS, lhsExprToExpr(target.target), Expression.IntLiteral("10")), id)
            
            // Inject new statements, but also cloning existing ones over
            for (i in 0..compound.statements.size) {
                if (i == min(index1, index2)) { newStatements.add(fixStatement) }
                if (i == max(index1, index2)) { newStatements.add(unfixStatement) }
                if (i < compound.statements.size) {
                    newStatements.add(
                        compound.statements[i].clone { node ->
                            if (node is Statement.Compound) {
                                recursiveInjectLocalVariableModifiers(
                                    lidExpr,
                                    node,
                                    injections,
                                    targetsVisibleInside(i),
                                )
                            } else {
                                null
                            }
                        },
                    )
                }
            }
            return Statement.Compound(newStatements, compound.metadata)
        }

        // For each global declaration, if it is a computer entry point, perform the transformation in the lambda
        val newGlobalDecls = mapComputeFunctions(shaderJob.tu.globalDecls) { decl ->
            val (nestingInfo, candidates) = findLocalVariableCandidates(decl.body) // All scopes nesting layers, All suitable variables' type and declaration info
            if (candidates.isEmpty()) return@mapComputeFunctions decl
            val selected = selectLocalVariableTargets(candidates)
            // Map<CompoundStatement, List<Pair<Target, Id>>>
            // Groups targets by their declaration compound
            val injectionsByCompound =
                selected
                    .map { it to fuzzerSettings.getUniqueId() }
                    .groupBy({ (target, _) -> target.declCompound }, { it })

            val (lidExpr, parameters) = getLidExpr(decl)
            
            // Recursively, starting from the outermost ie. the entrypoint function body
            val newBody =
                recursiveInjectLocalVariableModifiers(
                    lidExpr,
                    decl.body,
                    injectionsByCompound,
                )

            decl.withParametersAndBody(parameters, newBody)
        }
        return rebuildShaderJob(newGlobalDecls, listOf(inputStruct, inputBuffer))
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

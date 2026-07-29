package com.wgslfuzz.semanticspreservingtransformations

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
import com.wgslfuzz.core.traverse

// Injects pairs of statements of the form:
//   if (<divergent condition>) { <counter>++; }
//   if (<divergent condition>) { <counter>--; }
// into @compute entry points, where <divergent condition> is derived from local_invocation_id.
// Because the two ifs share the identical condition, each invocation either takes both branches or neither,
// so <counter> is always 0 by the time control leaves the pair -- the transformation is semantics preserving.
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
// <counter> is a SINGLE function-scope `var` declared as the first statement of the entry point
// body, shared by every injected pair in that entry point. Function-scope means each invocation
// owns its own copy, so there is no data race between invocations incrementing/decrementing "the
// same" variable.
//
// A correct compiler should always leaves the counter's initial value (999) as the final value, regardless the workgroup size.
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

// Guards the first-scalar walk against a pathological chain of nested aggregates
// (and against a struct that somehow refers to itself, which would otherwise not terminate).
private const val MAX_OUTPUT_NESTING_DEPTH = 16

// The single i32 member shared by v1's injected input (thread selector) and output (counter) structs.
// Read `thread_to_run.<member>` and Write `counter_output.<member>`
private const val V1_STRUCT_MEMBER = "data"

private class AddDivergentCounters(
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
     * Synthesizes a fresh entry point parameter carrying @builtin(local_invocation_id), plus an expression reading it.
     * Only called when [findExistingLID] found none, so this cannot introduce a second occurrence.
     */
    private fun synthesizeLocalInvocationIdParameter(): Pair<ParameterDecl, Expression> {
        val paramName = "divergent_counters_lid_${fuzzerSettings.getUniqueId()}"
        val parameter =
            ParameterDecl(
                attributes = listOf(Attribute.Builtin(BuiltinValue.LOCAL_INVOCATION_ID)),
                name = paramName,
                typeDecl = TypeDecl.Vec3(TypeDecl.U32()),
                metadata = setOf(AddedIdentifier(paramName)),
            )
        return parameter to Expression.Identifier(paramName)
    }

    /**
     * `local_invocation_id.x % Nu == 0u`. Splits invocations into two disjoint groups regardless of workgroup size. 
     * u32 (not i32) since local_invocation_id is vec3<u32>, so `.x` is u32, and WGSL's `%` requires both operands to share that type.
     */
    private fun modNCondition(n: Int = 2): Expression =
        Expression.Binary(
            operator = BinaryOperator.EQUAL_EQUAL,
            lhs =
                Expression.Binary(
                    operator = BinaryOperator.MODULO,
                    lhs = Expression.MemberLookup(lidExpr.clone(), "x"), // cloned so that distinct instances created every time new condition is created
                    rhs = Expression.IntLiteral("${n}u"),
                ),
            rhs = Expression.IntLiteral("0u"),
        )

    /**
     * Create <storage> input and output data.
     * 1. Input data that represents thread to run. To avoid read ie. data access to be statically evaluated during compile-time and then optimized out.
     * 2. Output data that represents the counter value.
     * Both are I32 type.
     */
    private fun createInputOutputForV1(
        inputBinding: String,
        outputBinding: String,
    ): Triple<GlobalDecl.Struct, GlobalDecl.Variable, GlobalDecl.Variable> {
        val structDecl = GlobalDecl.Struct(
            name = "DynamicData_${fuzzerSettings.getUniqueId()}",
            members = listOf(
                StructMember(
                    name = V1_STRUCT_MEMBER,
                    typeDecl = TypeDecl.I32(),
                ),
            ),
        )
        val inputInstance = GlobalDecl.Variable(
            attributes = listOf(
                Attribute.Group(Expression.IntLiteral(text="0")), 
                Attribute.Binding(Expression.IntLiteral(text=inputBinding))
                ),
            name = "thread_to_run",
            addressSpace = AddressSpace.STORAGE,
            accessMode = AccessMode.READ,
            typeDecl = TypeDecl.NamedType(structDecl.name),
            initializer = null,
        )

        val outputInstance = GlobalDecl.Variable(
            attributes = listOf(
                Attribute.Group(Expression.IntLiteral(text="0")), 
                Attribute.Binding(Expression.IntLiteral(text=outputBinding))
                ),
            name = "counter_output",
            addressSpace = AddressSpace.STORAGE,
            accessMode = AccessMode.READ_WRITE,
            typeDecl = TypeDecl.NamedType(structDecl.name),
            initializer = null,
        )
        return Triple(structDecl, inputInstance, outputInstance)
    }

    /**
     * Select only a single thread to run, early return for other threads.
     *
     * [threadSelector] reads the value from the injected input buffer (`thread_to_run.data`) rather
     * than being a literal, so the comparison cannot be constant-folded away at compile time
     * lid.x is u32, the selector is i32, so lid.x is converted to i32 to keep the comparison well-typed.
     */
    private fun singleThreadCondition(threadSelector: Expression, equals: Boolean): Expression =
        Expression.Binary(
            operator = if (equals) BinaryOperator.EQUAL_EQUAL else BinaryOperator.NOT_EQUAL,
            lhs = Expression.I32ValueConstructor(listOf(Expression.MemberLookup(lidExpr.clone(), "x"))),
            rhs = threadSelector.clone(),
        )

    /**
     * The counter's declaration: `var <counter>: i32 = 999i;`, to be placed first in the entry point body.
     *
     * 999 rather than 0 so that the expected end state is a distinctive constant. A buffer whose first
     * scalar legitimately holds 0 can no longer be mistaken for a passing instrumented run, and a shader
     * where [createCounterOverwrite] found nowhere to store the counter stands out instead of blending in.
     */
    private fun createCounterDeclaration(): Statement =
        Statement.Variable(
            name = counterName,
            typeDecl = TypeDecl.I32(),
            initializer = Expression.IntLiteral("999i"),
            metadata = setOf(AddedIdentifier(counterName)),
        )

    private fun createDivergentCounterPair(): List<Statement> {
        val id = fuzzerSettings.getUniqueId() // both conditions share a single id, so that both deleted together
        val lidValue = Expression.I32ValueConstructor(listOf(Expression.MemberLookup(lidExpr.clone(), "x")))
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
     * The shader's output buffer: the module-scope `var<storage, read_write>`.
     * wgslsmith emits exactly one (`s_output`); if a shader somehow has several, the first is used, since that is also the one the harness dumps first.
     */
    private fun findOutputBuffer(): GlobalDecl.Variable? =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Variable>()
            .firstOrNull { it.addressSpace == AddressSpace.STORAGE && it.accessMode == AccessMode.READ_WRITE }

    private fun zeroIndex(): Expression = Expression.IntLiteral("0i")
    
    /**
     * Walks [type] down to its first scalar leaf, extending [base] with the member/index lookups needed to name that leaf. 
     * Used to obtain expr and type of the 0th ele in output buffer
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
                val member =
                    shaderJob.tu.globalDecls
                        .filterIsInstance<GlobalDecl.Struct>()
                        .firstOrNull { it.name == type.name }
                        ?.members
                        ?.firstOrNull() ?: return null
                firstScalarLeaf(LhsExpression.MemberLookup(base, member.name), member.typeDecl, depth + 1)
            }
            else -> null
        }
    }

    /**
     * `<output>.<..first scalar..> = <scalarType>(<counter>);`
     *
     * Returns null when the shader has no output buffer.
     */
    private fun createCounterOverwrite(): Statement? {
        val outputBuffer = findOutputBuffer() ?: return null
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

    private fun selectInjectionPoints(
        node: AstNode,
        injections: DivergentCounterInjections,
    ) {
        traverse(::selectInjectionPoints, node, injections)
        // Within each compound statement, select a set of slots within which injections should go
        if (node is Statement.Compound) {
            injections[node] =
                (0..node.statements.size)
                    .filter { fuzzerSettings.injectDivergentCounter() }
                    .toSet()
        }
    }

    private fun injectDivergentCounters(
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
                    // Add original statements to newBody. Recursive to include injection into nested (compound) statements
                    newBody.add(statement.clone { injectDivergentCounters(it, injections, counterWrite) })
                }
            }
            Statement.Compound(newBody, compound.metadata)
        }

    fun applyV0(): ShaderJob {
        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                // Only focus on compute functions
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }

                val injections: DivergentCounterInjections = mutableMapOf()
                selectInjectionPoints(decl.body, injections)
                
                // Return original decl if it has no injections within
                if (injections.values.none { it.isNotEmpty() }) {
                    return@map decl
                }

                var parameters = decl.parameters
                val existing = findExistingLID(decl)
                lidExpr =
                    if (existing != null) {
                        existing
                    } else {
                        val (newParameter, expr) = synthesizeLocalInvocationIdParameter()
                        parameters = parameters + newParameter
                        expr
                    }

                counterName = "divergent_counter_${fuzzerSettings.getUniqueId()}"
                val counterWrite = createCounterOverwrite()
                val injectedBody = decl.body.clone { injectDivergentCounters(it, injections, counterWrite) }

                val statements = mutableListOf<Statement>()
                statements.add(createCounterDeclaration())
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
     * Largest @binding used by any module-scope variable in @group([group]), or null if the group binds nothing. 
     * Read straight off the AST attributes so it accounts for every buffer (including read_write outputs),
     *  not just those the pipeline state tracks values for ie. includes output buffers (ensure no buffer collision)
     */
    private fun largestBindingForGroup(group: Int): Int? =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Variable>()
            .filter { intAttribute(it, isGroup = true) == group }
            .mapNotNull { intAttribute(it, isGroup = false) }
            .maxOrNull()

    // Reads the @group (isGroup = true) / @binding (isGroup = false) integer
    // Returns null if the attribute is absent / not an integer literal.
    private fun intAttribute(v: GlobalDecl.Variable, isGroup: Boolean): Int? {
        for (attr in v.attributes) {
            val expr =
                when {
                    isGroup && attr is Attribute.Group -> attr.expression
                    !isGroup && attr is Attribute.Binding -> attr.expression
                    else -> null
                } ?: continue
            val lit = expr as? Expression.IntLiteral ?: continue
            return lit.text.trimEnd('i', 'u', 'U', 'I').toIntOrNull()
        }
        return null
    }

    fun applyV1(): ShaderJob {
        val prevBinding = largestBindingForGroup(0) ?: -1
        val inputBinding = (prevBinding + 1).toString()
        val outputBinding = (prevBinding + 2).toString()

        val (structDecl, inputInstance, outputInstance) =
            createInputOutputForV1(inputBinding, outputBinding)

        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                // Only focus on compute functions
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }

                val injections: DivergentCounterInjections = mutableMapOf()
                selectInjectionPoints(decl.body, injections)
                
                // Return original decl if it has no injections within
                if (injections.values.none { it.isNotEmpty() }) {
                    return@map decl
                }

                var parameters = decl.parameters
                val existing = findExistingLID(decl)
                lidExpr =
                    if (existing != null) {
                        existing
                    } else {
                        val (newParameter, expr) = synthesizeLocalInvocationIdParameter()
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
                val injectedBody = decl.body.clone { injectDivergentCounters(it, injections, counterWrite) }

                val statements = mutableListOf<Statement>()
                statements.add(createCounterDeclaration())
                statements.addAll(injectedBody.statements)
                if (counterWrite != null && injectedBody.statements.lastOrNull() !is Statement.Return) {
                    statements.add(counterWrite.clone())
                }

                // if (i32(lid.x) != thread_to_run.data) { return; } -- only the selected thread runs.
                statements.add(0,
                    Statement.If(
                        condition = singleThreadCondition(
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
}

fun addDivergentCountersV0(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentCounters(
        shaderJob,
        fuzzerSettings,
    ).applyV0()

fun addDivergentCountersV1(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentCounters(
        shaderJob,
        fuzzerSettings,
    ).applyV1()

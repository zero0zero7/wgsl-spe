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
import com.wgslfuzz.core.nodesPreOrder
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
// v2: similar to v1 except
// - Instead of a synthesized counter variable, an EXISTING local variable already used by the entry
//   point is hijacked as the target: it must be a local (function-scope `var`), reduced to a scalar
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
     * Create data structure for buffer to store thread to run, and counter value.
     */
    private fun createDataStruct(): GlobalDecl.Struct = GlobalDecl.Struct(
            name = "DynamicData_${fuzzerSettings.getUniqueId()}",
            members = listOf(
                StructMember(
                    name = V1_STRUCT_MEMBER,
                    typeDecl = TypeDecl.I32(),
                ),
            ),
        )
    /**
     * Create <storage> input data that represents thread to run. To avoid read ie. data access to be statically evaluated during compile-time and then optimized out.
     */
    private fun createInputInstance(
        inputBinding: String,
        structName: String
    ): GlobalDecl.Variable = GlobalDecl.Variable(
            attributes = listOf(
                Attribute.Group(Expression.IntLiteral(text="0")), 
                Attribute.Binding(Expression.IntLiteral(text=inputBinding))
                ),
            name = "thread_to_run",
            addressSpace = AddressSpace.STORAGE,
            accessMode = AccessMode.READ,
            typeDecl = TypeDecl.NamedType(structName),
            initializer = null,
        )
    /**
     * Create <storage> output data that will eventually hold counter value.
     */
    private fun createSingleOutputInstance(
        outputBinding: String,
        structName: String
    ): GlobalDecl.Variable = GlobalDecl.Variable(
            attributes = listOf(
                Attribute.Group(Expression.IntLiteral(text="0")), 
                Attribute.Binding(Expression.IntLiteral(text=outputBinding))
                ),
            name = "counter_output",
            addressSpace = AddressSpace.STORAGE,
            accessMode = AccessMode.READ_WRITE,
            typeDecl = TypeDecl.NamedType(structName),
            initializer = null,
        )


    /**
     * Create data structure for output buffer to store for multiple threads
     * Fixed-size array of [V2_OUTPUT_ARRAY_SIZE] i32 slots, rather than a single shared scalar
     * 1 slot per thread (indexed by local_invocation_index) -- to allow multi-thread execution where every invocation needs its own slot to avoid racing on the write.
     */
    private fun createMultiOutputStruct(): GlobalDecl.Struct = GlobalDecl.Struct(
                name = "MultiOutput_${fuzzerSettings.getUniqueId()}",
                members = listOf(
                    StructMember(
                        name = V2_STRUCT_MEMBER,
                        typeDecl = TypeDecl.Array(
                            elementType = TypeDecl.I32(),
                            elementCount = Expression.IntLiteral("$V2_OUTPUT_ARRAY_SIZE"),
                        ),
                    ),
                ),
            )

    /**
     * Create <storage> output data for multi-threaded execution
     */
    private fun createMultiOutputInstance(
        outputBinding: String,
        structName: String
    ): GlobalDecl.Variable = GlobalDecl.Variable(
                attributes = listOf(
                    Attribute.Group(Expression.IntLiteral(text = "0")),
                    Attribute.Binding(Expression.IntLiteral(text = outputBinding)),
                ),
                name = "multi_counter_output",
                addressSpace = AddressSpace.STORAGE,
                accessMode = AccessMode.READ_WRITE,
                typeDecl = TypeDecl.NamedType(structName),
                initializer = null,
            )

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

    private fun createIncrementStatement(
        targetName: String,
        magnitude: Expression,
        id: Int,
    ): Statement.If =
        Statement.If(
            condition = modNCondition(n = 1),
            thenBranch =
                Statement.Compound(
                    listOf(
                        Statement.Assignment(
                            lhsExpression = LhsExpression.Identifier(targetName),
                            assignmentOperator = AssignmentOperator.PLUS_EQUAL,
                            rhs = Expression.IntLiteral("3"),
                        ),
                    ),
                ),
        )

    private fun createDecrementStatement(
        targetName: String,
    ): Statement.If =
        Statement.If(
            condition = modNCondition(n = 1),
            thenBranch =
                Statement.Compound(
                    listOf(
                        Statement.Assignment(
                            lhsExpression = LhsExpression.Identifier(targetName),
                            assignmentOperator = AssignmentOperator.MINUS_EQUAL,
                            rhs = Expression.IntLiteral("3"),
                        ),
                    ),
                ),
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
     * Converts an LhsExpression back into a readable Expression, following the same path
     * (identifier/member/index/paren). [firstScalarLeaf] only ever builds targets out of these node
     * kinds, so Dereference/AddressOf are unreachable here.
     */
    private fun lhsExprToExpr(lhs: LhsExpression): Expression =
        when (lhs) {
            is LhsExpression.Identifier -> Expression.Identifier(lhs.name)
            is LhsExpression.Paren -> Expression.Paren(lhsExprToExpr(lhs.target))
            is LhsExpression.MemberLookup -> Expression.MemberLookup(lhsExprToExpr(lhs.receiver), lhs.memberName)
            is LhsExpression.IndexLookup -> Expression.IndexLookup(lhsExprToExpr(lhs.target), lhs.index.clone())
            is LhsExpression.Dereference, is LhsExpression.AddressOf ->
                error("firstScalarLeaf never produces a Dereference/AddressOf target")
        }

    /**
     * `if (<indexExpr> < V2_OUTPUT_ARRAY_SIZEu) { <outputBufferName>.data[<indexExpr>] = i32(<target>); }`
     * Bounds-checked so a workgroup with more threads than [V2_OUTPUT_ARRAY_SIZE] just skips the write for its excess threads
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

    private fun zeroIndex(): Expression = Expression.IntLiteral("0i")
    
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
     * `<output>.<..first scalar..> = <scalarType>(<counter>);`
     * Returns null when the shader has no output buffer. (v0)
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

    /** The variable name at the root of an lvalue chain (through member/index/paren/deref), or null. */
    private fun lhsBaseIdentifierName(lhs: LhsExpression?): String? =
        when (lhs) {
            is LhsExpression.Identifier -> lhs.name
            is LhsExpression.MemberLookup -> lhsBaseIdentifierName(lhs.receiver)
            is LhsExpression.IndexLookup -> lhsBaseIdentifierName(lhs.target)
            is LhsExpression.Paren -> lhsBaseIdentifierName(lhs.target)
            is LhsExpression.Dereference -> lhsBaseIdentifierName(lhs.target)
            is LhsExpression.AddressOf -> lhsBaseIdentifierName(lhs.target)
            null -> null
        }

    /** True if [expr] contains a genuine read of `name` (an Expression.Identifier) as opposed to an Lhs value (which could be a write). */
    private fun expressionReadsIdentifier(
        expr: Expression,
        name: String,
    ): Boolean = nodesPreOrder(expr).any { it is Expression.Identifier && it.name == name }

    /**
     * True if [statement] reads [name]. Every Expression.Identifier occurrence is a genuine read
     */
    private fun statementReadsIdentifier(
        statement: Statement,
        name: String,
    ): Boolean {
        for (node in nodesPreOrder(statement)) {
            if (node is Expression.Identifier && node.name == name) return true
            // For compound assignment (self-referential) statements, the lhs is also considered a read
            if (node is Statement.Assignment && node.assignmentOperator != AssignmentOperator.EQUAL) {
                val lhsName = lhsBaseIdentifierName(node.lhsExpression)
                if (lhsName == name) return true
            }
        }
        return false
    }

    /**
     * True if [statement] is a jump that could leave the enclosing loop/function before a later
     * statement in the same Compound ie. scope runs (Break, Return, Discard). Continue is deliberately excluded:
     * it only skips to the next loop iteration, and the write refreshes the variable before it's read again,
     * so a decrement stranded by a Continue is never actually observed by a stale read.
     */
    private fun isDisqualifyingExit(statement: Statement): Boolean =
        statement is Statement.Break || statement is Statement.Return || statement is Statement.Discard

    /** True if [statement] is a plain (`=`) reassignment of [name] that does NOT also read it
     *  Excludes compound assignment, which is captured as a READ
     */
    private fun statementReassignsIdentifier(
        statement: Statement,
        name: String,
    ): Boolean =
        statement is Statement.Assignment &&
            statement.assignmentOperator == AssignmentOperator.EQUAL &&
            (statement.lhsExpression as? LhsExpression.Identifier)?.name == name

    /**
     * Index of the first statement after [fromIndex] that reads [name], or null if:
     * - it's never read, or
     * - a Break/Return/Discard sits between [fromIndex] and that read could let an increment fire without its paired decrement ever running, or
     * - if a write is encountered, just continue searching for a read
     */
    private fun collectReadIndices(
        statements: List<Statement>,
        fromIndex: Int,
        name: String,
    ): List<Int> {
        val reads = mutableListOf<Int>()
        for (index in (fromIndex + 1) until statements.size) {
            val statement = statements[index]
            if (isDisqualifyingExit(statement)) return reads
            if (statementReadsIdentifier(statement, name)) reads.add(index)
        }
        return reads
    }

    /** Describes one selected local-variable target inside a single Compound.
     * [declIndex] is null when the injection scope is a descendant of the declaring scope
     */
    private data class LocalVariableTarget(
        val compound: Statement.Compound,
        val target: LhsExpression,
        val targetType: TypeDecl.ScalarTypeDecl,
        val declIndex: Int?,
        val readIndices: Int,
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
     * Collects, from [node] downward, every identifier genuinely read "in the current scope" -- ie.
     * without crossing into a nested Statement.Compound, which is a scope boundary of its own and
     * gets walked separately by the caller. Also collects every Compound reachable this way, so the
     * caller knows which nested scopes to recurse into next. Mirrors statementReadsIdentifier's
     * handling of compound assignment (`x += ...`) counting as a read of `x`.
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
     * Walks [path] (ordered outer -> inner: [0] is the declaring scope, last is the read's own
     * innermost scope), starting at the declaring scope and flipping a coin at each nested-scope
     * boundary: 50% chance to descend further toward the read, 50% chance to stop at the current
     * scope. Geometrically biases toward outer scopes while still being able to reach all the way in.
     */
    private fun chooseInjectionScope(path: List<Statement.Compound>): Statement.Compound {
        var chosen = 0
        for (i in 1 until path.size) {
            if (!fuzzerSettings.randomBool()) break
            chosen = i
        }
        return path[chosen]
    }

    /**
     * Walks [body], treating every Statement.Compound as a lexical scope, to find local `var`
     * declarations that resolve to a scalar via firstScalarLeaf. Each selected target is rewritten
     * in the same Compound that contains the declaration, with the increment/decrement pair spliced
     * in immediately after the declaration statement itself.
     */
    private fun findLocalVariableCandidates(body: Statement.Compound): List<LocalVariableTarget> {
        fun walk(
            compound: Statement.Compound,
            targets: MutableList<LocalVariableTarget>,
        ) {
            val statements = compound.statements

            for (index in statements.indices) {
                val statement = statements[index]
                val info = DirectScopeInfo()
                collectDirectScopeInfo(statement, info)

                if (statement is Statement.Variable) {
                    statement.typeDecl?.let { typeDecl ->
                        firstScalarLeaf(LhsExpression.Identifier(statement.name), typeDecl, 0)?.let { (target, targetType) ->
                            targets.add(
                                LocalVariableTarget(
                                    compound = compound,
                                    target = target,
                                    targetType = targetType,
                                    declIndex = index,
                                    readIndices = index,
                                ),
                            )
                        }
                    }
                }

                if (info.nestedCompounds.isNotEmpty()) {
                    for (nested in info.nestedCompounds) {
                        walk(nested, targets)
                    }
                }
            }
        }

        val targets = mutableListOf<LocalVariableTarget>()
        walk(body, targets)
        return targets
    }

    /**
     * Applies fuzzerSettings' random gate to each candidate independently (mirrors the per-slot
     * injectDivergentCounter() gating v0/v1 use for their counter-pair injection points).
     */
    private fun selectLocalVariableTargets(candidates: List<LocalVariableTarget>): List<LocalVariableTarget> =
        candidates.filter { fuzzerSettings.injectDivergentCounter() }

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

    /** Bundles what [injectLocalVariableCounters] needs to thread through the recursive clone. */
    private data class V2InjectionContext(
        val injectionsByCompound: Map<Statement.Compound, List<Pair<LocalVariableTarget, Int>>>,
        val magnitude: Expression,
        val outputBufferName: String,
        val indexExpr: Expression,
    )

    /**
     * Rewrites every Compound present in [V2InjectionContext.injectionsByCompound], splicing in
     * the increment/decrement pair immediately after each selected target declaration.
     * All targets for a Compound are handled in one pass so recursive rewrites of nested
     * statements do not disturb the original statement indices.
     */
    private fun injectLocalVariableCounters(
        node: AstNode,
        ctx: V2InjectionContext,
    ): AstNode? =
        ctx.injectionsByCompound[node]?.let { targets ->
            val compound = node as Statement.Compound
            val newStatements = mutableListOf<Statement>()
            for (index in compound.statements.indices) {
                val statement = compound.statements[index]
                newStatements.add(statement.clone { injectLocalVariableCounters(it, ctx) })

                if (statement is Statement.Variable) {
                    val targetName = statement.name
                    for ((target, id) in targets) {
                        if (target.declIndex != index) continue
                        if (lhsBaseIdentifierName(target.target) != targetName) continue
                        newStatements.add(createIncrementStatement(targetName, ctx.magnitude, id))
                        newStatements.add(createDecrementStatement(targetName))
                    }
                }
            }
            Statement.Compound(newStatements, compound.metadata)
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

        val structDecl = createDataStruct()
        val inputInstance = createInputInstance(inputBinding, structDecl.name)
        val outputInstance = createSingleOutputInstance(outputBinding, structDecl.name)

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
    /**
     * v2: hijacks an existing local variable's write/read pair rather than injecting a fresh counter,
     * and lets every invocation actually run concurrently (no early-return gating) since each
     * invocation now owns its own output slot -- see [createInputOutputForV2] / [createIndexedCounterWrite].
     */
    fun applyV2(): ShaderJob {
        val prevBinding = largestBindingForGroup(0) ?: -1
        val inputBinding = (prevBinding + 1).toString()
        val outputBinding = (prevBinding + 2).toString()

        val inputStruct = createDataStruct()
        val inputBuffer = createInputInstance(inputBinding, inputStruct.name)
        val outputStruct = createMultiOutputStruct()
        val outputBuffer = createMultiOutputInstance(outputBinding, outputStruct.name)

        val magnitude = Expression.MemberLookup(Expression.Identifier(inputBuffer.name), V2_STRUCT_MEMBER)

        val newGlobalDecls =
            shaderJob.tu.globalDecls.map { decl ->
                // Only focus on compute functions
                if (decl !is GlobalDecl.Function || decl.attributes.none { it is Attribute.Compute }) {
                    return@map decl
                }

                val candidates = findLocalVariableCandidates(decl.body)
                val selected = selectLocalVariableTargets(candidates)

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
                        val (newParameter, expr) = synthesizeLocalInvocationIdParameter()
                        parameters = parameters + newParameter
                        expr
                    }

                val existingIndex = findExistingLID(decl)
                val indexExpr =
                    if (existingIndex != null) {
                        existingIndex
                    } else {
                        val (newParameter, expr) = synthesizeLocalInvocationIdParameter()
                        parameters = parameters + newParameter
                        expr
                    }

                val injectionsByCompound =
                    selected
                        .map { it to fuzzerSettings.getUniqueId() }
                        .groupBy({ (target, _) -> target.compound }, { it })

                val ctx =
                    V2InjectionContext(
                        injectionsByCompound = injectionsByCompound,
                        magnitude = magnitude,
                        outputBufferName = outputBuffer.name,
                        indexExpr = indexExpr,
                    )
                val injectedBody = decl.body.clone { injectLocalVariableCounters(it, ctx) }

                GlobalDecl.Function(
                    attributes = decl.attributes,
                    name = decl.name,
                    parameters = parameters,
                    returnAttributes = decl.returnAttributes,
                    returnType = decl.returnType,
                    body = injectedBody,
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

fun addDivergentCountersV2(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
): ShaderJob =
    AddDivergentCounters(
        shaderJob,
        fuzzerSettings,
    ).applyV2()

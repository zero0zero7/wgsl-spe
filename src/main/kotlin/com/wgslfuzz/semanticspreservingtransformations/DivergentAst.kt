package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddedIdentifier
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.AssignmentOperator
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AugmentedMetadata
import com.wgslfuzz.core.BuiltinValue
import com.wgslfuzz.core.clone
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.ParameterDecl
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.StructMember
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.TypeDecl

// Pure AST constructors for the divergent-injection transformations: no randomness, no policy,
// no reads of the shader being transformed. 
// Anything that has to *choose* belongs in DivergentPerturbations.kt / DivergentConditions.kt;
// Anything that has to *inspect* the shader belongs in DivergentTargets.kt.

/**
 * Data structure for the injected buffers: the thread selector (input) and the counter (output).
 */
internal fun dataStruct(id: Int = -1): GlobalDecl.Struct =
    GlobalDecl.Struct(
        name = if (id >= 0) "DivergentInput_$id" else "DivergentInput",
        members =
            listOf(
                // The thread the single-thread gate admits ie. the only thread to run.
                StructMember(name = V1_STRUCT_MEMBER, typeDecl = TypeDecl.U32()),
            ) +
                // zero/one/min/max for i32, u32 and f32, read at runtime so that an expression
                // built from them cannot be constant-folded.
                HIDDEN_CONSTANT_MEMBERS.map { (name, scalar) ->
                    StructMember(
                        name = name,
                        typeDecl =
                            when (scalar.suffix) {
                                "i32" -> TypeDecl.I32()
                                "u32" -> TypeDecl.U32()
                                else -> TypeDecl.F32()
                            },
                    )
                },
    )

/**
 * Create `var<storage, read>` input data holding the thread to run.
 */
internal fun threadToRunInputInstance(
    inputBinding: String,
    structName: String,
): GlobalDecl.Variable =
    GlobalDecl.Variable(
        attributes =
            listOf(
                Attribute.Group(Expression.IntLiteral(text = "0")),
                Attribute.Binding(Expression.IntLiteral(text = inputBinding)),
            ),
        name = "thread_to_run",
        addressSpace = AddressSpace.STORAGE,
        accessMode = AccessMode.READ,
        typeDecl = TypeDecl.NamedType(structName),
        initializer = null,
    )

/**
 * Create `var<storage, read_write>` output data.
 */
internal fun scalarOutputInstance(
    outputBinding: String,
    structName: String,
    outputName: String,
): GlobalDecl.Variable =
    GlobalDecl.Variable(
        attributes =
            listOf(
                Attribute.Group(Expression.IntLiteral(text = "0")),
                Attribute.Binding(Expression.IntLiteral(text = outputBinding)),
            ),
        name = outputName,
        addressSpace = AddressSpace.STORAGE,
        accessMode = AccessMode.READ_WRITE,
        typeDecl = TypeDecl.NamedType(structName),
        initializer = null,
    )

/**
 * `var <counter>: u32 = 993u;`
 * To be placed first in the entry point body.
 * Non-zero so that the expected end state is a distinctive constant; see [COUNTER_INITIAL_VALUE].
 */
internal fun counterInstance(
    value: Int = COUNTER_INITIAL_VALUE,
    counterName: String,
): Statement =
    Statement.Variable(
        name = counterName,
        typeDecl = TypeDecl.U32(),
        initializer = Expression.IntLiteral("${value}u"),
        metadata = setOf(AddedIdentifier(counterName)),
    )

/**
 * Synthesizes a fresh entry point parameter carrying @builtin(local_invocation_id), plus an
 * expression reading it. 
 * Only called when [findExistingLID] found none, to avoid introducing a second occurrence.
 */
internal fun lidParameter(suffix: Int = -1): Pair<ParameterDecl, Expression> {
    val paramName = if (suffix >= 0) "divergent_lid_$suffix" else "divergent_lid"
    val parameter =
        ParameterDecl(
            attributes = listOf(Attribute.Builtin(BuiltinValue.LOCAL_INVOCATION_ID)),
            name = paramName,
            typeDecl = TypeDecl.Vec3(TypeDecl.U32()), // i + (j * workgroup_size_x) + (k * workgroup_size_x * workgroup_size_y)
            metadata = setOf(AddedIdentifier(paramName)),
        )
    return parameter to Expression.Identifier(paramName)
}

/**
 * `if (<guard>) { <body> }`
 */
internal fun guardedStatement(
    guard: Expression,
    body: List<Statement>,
    id: Int,
    commentary: String,
): Statement.If =
    Statement.If(
        condition = guard,
        thenBranch = Statement.Compound(body),
        metadata = setOf(AugmentedMetadata.DeletableStatement(id, commentary)),
    )

/** `<target> = <value>;` */
internal fun assign(
    target: LhsExpression,
    value: Expression,
): Statement.Assignment =
    Statement.Assignment(
        lhsExpression = target.clone(),
        assignmentOperator = AssignmentOperator.EQUAL,
        rhs = value,
    )

/** `if (<guard>) { <target> = <newValue>; }`
* Single-assignment case of [guardedStatement]. 
*/
internal fun perturbationStatement(
    guard: Expression,
    target: LhsExpression,
    newValue: Expression,
    id: Int,
    commentary: String,
): Statement.If =
    guardedStatement(
        guard = guard,
        body = listOf(assign(target, newValue)),
        id = id,
        commentary = commentary,
    )

/**
 * The intermediary variable a SnapshotTemplate or TempCopyTemplate introduces. 
 * Named from the pair's id, so two pairs in the same scope can never collide.
 */
internal fun injectedTempName(id: Int): String = "injected_$id"

/**
 * `let injected_<id> = <target>;`
 *
 * Hoisted into the ENCLOSING compound, BEFORE and OUTSIDE the perturb guard,
 * so that it is in the scope of the restoration. 
 */
internal fun snapshotDeclaration(
    name: String,
    target: LhsExpression,
    id: Int,
    commentary: String,
): Statement.Value =
    Statement.Value(
        isConst = false,
        name = name,
        initializer = lhsExprToExpr(target),
        metadata = setOf(AugmentedMetadata.DeletableStatement(id, commentary), AddedIdentifier(name)),
    )

/**
 * `var injected_<id> : <type>;`
 *
 * Same hoisting requirement as [snapshotDeclaration], but mutable and left uninitialised: 
 * - WGSL zero-initialises a function-scope `var`,
 * - only written under the restore guard
 */
internal fun scratchDeclaration(
    name: String,
    type: Type.Scalar,
    id: Int,
    commentary: String,
): Statement.Variable =
    Statement.Variable(
        name = name,
        typeDecl = scalarTypeDecl(type),
        metadata = setOf(AugmentedMetadata.DeletableStatement(id, commentary), AddedIdentifier(name)),
    )

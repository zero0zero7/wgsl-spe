package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.AccessMode
import com.wgslfuzz.core.AddressSpace
import com.wgslfuzz.core.AssignmentOperator
import com.wgslfuzz.core.AstNode
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.BuiltinValue
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.ParameterDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.TranslationUnit
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.TypeDecl
import com.wgslfuzz.core.asStoreTypeIfReference
import com.wgslfuzz.core.clone
import com.wgslfuzz.core.nodesPreOrder
import com.wgslfuzz.core.toType
import com.wgslfuzz.core.traverse

// Read-only analysis over the shader being transformed, plus the small helpers that
// shape an lvalue or a declaration list. 
// Nothing here injects anything or draws a random number;
// the two catalogues and the two injection strategies build on it.

// ---------- per-entry-point state ----------

/**
 * Everything an injection site needs to know about the entry point currently being instrumented.
 *
 * Built once per entry point inside each applyV*'s [mapComputeFunctions] lambda and threaded as an
 * explicit parameter.
 */
internal class EntryPointContext(
    private val lidExpr: Expression,
    /** decl.parameters, extended with a synthesised lid ParameterDecl when one is needed. */
    val parameters: List<ParameterDecl>,
    /** The synthesised i32 counter local. Null for v2, which hijacks an existing var instead. */
    val counterName: String? = null,
    /**
     * Reads a runtime i32 that no compiler can constant-fold (v1/v2's `thread_to_run.data`).
     * Null for v0, which injects no input buffer. Each call must return a FRESH expression.
     */
    private val opaqueThreadExpr: (() -> Expression)? = null,
    /**
     * Reads one member of the injected input buffer's hidden-constant block by name, or null when
     * this entry point has no such buffer (v0). Each call must return a FRESH expression.
     */
    private val hiddenConstant: ((String) -> Expression)? = null,
) {
    /** Fresh clone every time; the stored node is never handed out, so no AST node is shared. */
    fun lid(): Expression = lidExpr.clone()

    fun counter(): LhsExpression =
        LhsExpression.Identifier(requireNotNull(counterName) { "this entry point has no synthesised counter" })

    fun counterExpr(): Expression =
        Expression.Identifier(requireNotNull(counterName) { "this entry point has no synthesised counter" })

    fun opaque(): Expression? = opaqueThreadExpr?.invoke()

    /**
     * `<input>.<kind>_<type>`, eg. `thread_to_run.zero_u32`. Null when this entry point has no
     * injected input buffer, or when [type] has no hidden-constant member (f16).
     *
     * Uniform: a `var<storage, read>` load is the same in every invocation. It
     * defeats constant folding, nothing more.
     */
    fun hidden(
        kind: String, // min/zero/one/max
        type: Type.Scalar,
    ): Expression? {
        val member = hiddenMemberFor(kind, type) ?: return null
        return hiddenConstant?.invoke(member)
    }
}

// ---------- locating local_invocation_id ----------

/**
 * Finds an expression that reads local_invocation_id from an EXISTING parameter 
 * Returns null if none is present yet.
 */
internal fun findExistingLID(
    shaderJob: ShaderJob,
    function: GlobalDecl.Function,
): Expression? {
    for (parameter in function.parameters) {
        val hasLID = parameter.attributes.filterIsInstance<Attribute.Builtin>().any { it.name == BuiltinValue.LOCAL_INVOCATION_ID }
        if (hasLID) {
            return Expression.Identifier(parameter.name)
        }
    }
    // Find LID embedded within a struct parameter
    for (parameter in function.parameters) {
        val structName = (parameter.typeDecl as? TypeDecl.NamedType)?.name ?: continue
        val structDecl = // Find the struct decl with the matching name
            shaderJob.tu.globalDecls
                .filterIsInstance<GlobalDecl.Struct>()
                .firstOrNull { it.name == structName } ?: continue
        val member = // Find the member of the struct that is local_invocation_id
            structDecl.members.firstOrNull { member ->
                member.attributes.filterIsInstance<Attribute.Builtin>().any { it.name == BuiltinValue.LOCAL_INVOCATION_ID }
            } ?: continue
        return Expression.MemberLookup(Expression.Identifier(parameter.name), member.name)
    }
    return null
}

/**
 * Finds / synthesizes the local_invocation_id expression for [functionDecl], returning it
 * alongside the (possibly extended) parameter list.
 */
internal fun getLidExpr(
    shaderJob: ShaderJob,
    fuzzerSettings: FuzzerSettings,
    functionDecl: GlobalDecl.Function,
): Pair<Expression, List<ParameterDecl>> {
    val existing: Expression? = findExistingLID(shaderJob, functionDecl)
    if (existing != null) {
        return existing to functionDecl.parameters
    }
    // Create if not exist in original shader
    val (newParameter, expr) = lidParameter(fuzzerSettings.getUniqueId())
    return expr to (functionDecl.parameters + newParameter)
}

// ---------- finding a scalar target ----------

/**
 * Walks [base] of [type] down to its first scalar leaf, extending [base] with the member/index lookups needed to name that leaf.
 */
internal fun firstScalarLeaf(
    base: LhsExpression,
    type: Type,
    depth: Int,
): Pair<LhsExpression, Type.Scalar>? {
    if (depth > MAX_OUTPUT_NESTING_DEPTH) {
        return null
    }
    return when (type) {
        // Reject Boolean and abstract types, since they can't be the target of a perturbation.
        Type.Bool, Type.AbstractInteger, Type.AbstractFloat -> null
        is Type.Scalar -> base to type
        is Type.Vector ->
            if (type.elementType == Type.Bool || type.elementType.isAbstract()) { null } 
            else { LhsExpression.IndexLookup(base, zeroIndex()) to type.elementType }
        is Type.Matrix ->
            if (type.elementType.isAbstract()) { null } 
            else { LhsExpression.IndexLookup(LhsExpression.IndexLookup(base, zeroIndex()), zeroIndex()) to type.elementType }
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

// ---------- v2: local-variable candidate selection ----------

/**
 * Describes one selected local-variable target inside a single Compound.
 * [declIndex] is null when the injection scope is a descendant of the declaring scope.
 */
internal data class LocalVariableTarget(
    val target: LhsExpression,
    val targetType: Type.Scalar,
    val declCompound: Statement.Compound,
    val declIndex: Int?, // declaration index within the compound
)

internal class CompoundInfo (
    val compound: Statement.Compound,
) {
    val reads = mutableListOf<Pair<String, Int>>() // Each pair is identifierName, index of read in CURRENT compound
    val childrenCompound = mutableListOf<CompoundInfo>()
}


// internal fun collectReads(
//     node: AstNode,
//     reads: MutableList<String>, // Names of local variables read in given compound, given index ie. specific statement in compound
// ): List<String> {
//     when (node) {
//         is Statement.Compound -> throw IllegalArgumentException("collectReads must not be called on a Compound; nested scopes are handled by the caller")
//         is Expression.Identifier -> reads.add(node.name)
//         is Expression.ValueConstructor -> reads.add(node.constructorName)
//         else -> {}
//     }
//     traverse(::collectReads, node, reads)
//     return reads
// }

/**
 * For v2. Walks [body], treating every Statement.Compound as a lexical scope, and finds all local
 * `var` declarations that resolve to a scalar via [firstScalarLeaf], including those in nested scopes.
 */
internal fun findLocalVariableCandidates(
    shaderJob: ShaderJob,
    body: Statement.Compound,
): Pair<CompoundInfo, List<LocalVariableTarget>> {
    // Cannot use `traverse()` alone as we want to capture the INDEX of the line in the Statement.Compound
    fun walk(
        compoundInfo: CompoundInfo,
        declarations: MutableList<LocalVariableTarget>,
    ) {
        /** Helper function to collect reads of variables that have been declared
         * Not a standalone function as it requires knowledge of declared vars
         */
        fun collectReads(
            node: AstNode,
            reads: MutableList<String>, // Names of local variables read in given compound, given index ie. specific statement in compound
        ): List<String> {
            // A read must name one of the declarations collected so far; ignore non-locals
            fun record(name: String) {
                if (declarations.any { lhsBaseIdentifierName(it.target) == name }) {
                    reads.add(name)
                }
            }
            when (node) {
                is Statement.Compound -> throw IllegalArgumentException("collectReads must not be called on a Compound; nested scopes are handled by the caller")
                is Expression.Identifier -> record(node.name)
                is Expression.ValueConstructor -> record(node.constructorName)
                else -> {}
            }
            traverse(::collectReads, node, reads)
            return reads
        }

        val statements = compoundInfo.compound.statements
        for (index in statements.indices) {
            val statement = statements[index]
            if (statement is Statement.Compound) {
                compoundInfo.childrenCompound.add(
                    CompoundInfo(compound=statement)
                )
                continue
            }
            // For each statement (that is not a statement.compound) in the compound, collect all *identifiers read* in that statement's scope
            collectReads(statement, mutableListOf<String>()).forEach { readName ->
                compoundInfo.reads.add(readName to index)
            }
            // Collect local variable declaration, and add it as a candidate if it resolves to a scalar
            if (statement is Statement.Variable) {
                val variableType = 
                    statement.typeDecl?.toType(shaderJob.environment.globalScope, shaderJob.environment) // Variable type explicitly declared
                        ?: statement.initializer?.let { shaderJob.environment.typeOf(it).asStoreTypeIfReference() } // If not, infer from initializer
                // Extract numeric scalar leaf of variable using the type
                variableType?.let { type ->
                    firstScalarLeaf(LhsExpression.Identifier(statement.name), type, 0)?.let { (target, targetType) ->
                        declarations.add(
                            LocalVariableTarget(
                                declCompound = compoundInfo.compound,
                                target = target,
                                targetType = targetType,
                                declIndex = index,
                            ),
                        )
                    }
                }
            } else if (isDisqualifyingExit(statement)) {
                // Stop walking the compound at an exit: code after it might or might not run.
                break
            }
        }
        for (idx in compoundInfo.childrenCompound.indices) {
            val comp = compoundInfo.childrenCompound[idx]
            walk(comp, declarations)
        }
    }

    val declarations = mutableListOf<LocalVariableTarget>()
    val rootCompoundInfo = CompoundInfo(compound=body)
    walk(rootCompoundInfo, declarations)
    return rootCompoundInfo to declarations
}

// ---------- binding allocation and job rebuilding ----------

/**
 * Last-assigned @binding used by any module-scope variable in @group([group]),
 *  or null if the group binds nothing. 
 * Reads straight off the AST attributes to account for every buffer 
 * (including read_write output buffers, not just those the pipeline state tracks values for)
 */
internal fun lastBindingForGroup(
    shaderJob: ShaderJob,
    group: Int,
): Int? =
    shaderJob.tu.globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .filter { intAttribute(it, isGroup = true) == group }
        .mapNotNull { intAttribute(it, isGroup = false) }
        .maxOrNull()

/** The next two free @binding indices in @group(0) -- used by v1/v2 for their extra buffers. */
internal fun nextTwoBindings(shaderJob: ShaderJob): Pair<String, String> {
    val prevBinding = lastBindingForGroup(shaderJob, 0) ?: -1
    return (prevBinding + 1).toString() to (prevBinding + 2).toString()
}

/** Re-wraps [newGlobalDecls] (optionally preceded by injected structs/buffers) into a ShaderJob. */
internal fun rebuildShaderJob(
    shaderJob: ShaderJob,
    newGlobalDecls: List<GlobalDecl>,
    extraGlobalDecls: List<GlobalDecl> = emptyList(),
): ShaderJob =
    ShaderJob(
        tu = TranslationUnit(shaderJob.tu.directives, extraGlobalDecls + newGlobalDecls, shaderJob.tu.metadata),
        pipelineState = shaderJob.pipelineState,
    )

// ---------- lvalue and attribute helpers ----------

/**
 * Converts an LhsExpression back into a readable Expression, following the same path.
 * [firstScalarLeaf] gives the target variable as an LhsExpression, but the perturb/restore statements need a regular Expression.
 */
internal fun lhsExprToExpr(lhs: LhsExpression): Expression =
    when (lhs) {
        is LhsExpression.Identifier -> Expression.Identifier(lhs.name)
        is LhsExpression.Paren -> Expression.Paren(lhsExprToExpr(lhs.target))
        is LhsExpression.MemberLookup -> Expression.MemberLookup(lhsExprToExpr(lhs.receiver), lhs.memberName)
        is LhsExpression.IndexLookup -> Expression.IndexLookup(lhsExprToExpr(lhs.target), lhs.index.clone())
        is LhsExpression.Dereference, is LhsExpression.AddressOf ->
            error("firstScalarLeaf never produces a Dereference/AddressOf target")
    }

/** The variable name at the root of an lvalue chain (through member/index/paren/deref), or null. 
 * Unlike [lhsExprToExpr], [lhsBaseIdentifierName] is used to detect reads of a variable in a statement,
 *  even if the read is through a member/index.
 */
internal fun lhsBaseIdentifierName(lhs: LhsExpression?): String? =
    when (lhs) {
        is LhsExpression.Identifier -> lhs.name
        is LhsExpression.MemberLookup -> lhsBaseIdentifierName(lhs.receiver)
        is LhsExpression.IndexLookup -> lhsBaseIdentifierName(lhs.target)
        is LhsExpression.Paren -> lhsBaseIdentifierName(lhs.target)
        is LhsExpression.Dereference -> lhsBaseIdentifierName(lhs.target)
        is LhsExpression.AddressOf -> lhsBaseIdentifierName(lhs.target)
        null -> null
    }

/**
 * True if [statement] is a jump that could leave the enclosing loop/function before a later
 * statement in the same Compound (ie. scope) runs: Break, Return, Discard.
 *
 * Continue is deliberately excluded: it only skips to the next loop iteration, and the write
 * refreshes the variable before it is read again, so a restore stranded by a Continue is never
 * actually observed by a stale read.
 */
internal fun isDisqualifyingExit(statement: Statement): Boolean =
    statement is Statement.Break || statement is Statement.Return || statement is Statement.Discard

internal fun zeroIndex(): Expression = Expression.IntLiteral("0i")

/**
 * Reads the @group (isGroup = true) / @binding (isGroup = false) integer.
 * Returns null if the attribute is absent or not an integer literal.
 */
internal fun intAttribute(
    globalVar: GlobalDecl.Variable,
    isGroup: Boolean,
): Int? {
    for (attr in globalVar.attributes) {
        val expr =
            when {
                isGroup && attr is Attribute.Group -> attr.expression
                !isGroup && attr is Attribute.Binding -> attr.expression
                else -> null
            } ?: continue
        val literal = expr as? Expression.IntLiteral ?: continue
        return literal.text.trimEnd('i', 'u', 'U', 'I').toIntOrNull()
    }
    return null
}

/** Maps every @compute entry point through [transform]; every other decl passes through unchanged. */
internal inline fun mapComputeFunctions(
    globalDecls: List<GlobalDecl>,
    transform: (GlobalDecl.Function) -> GlobalDecl,
): List<GlobalDecl> =
    globalDecls.map { decl ->
        if (decl is GlobalDecl.Function && decl.attributes.any { it is Attribute.Compute }) {
            transform(decl)
        } else {
            decl
        }
    }

/** Rebuilds [this] with a new parameter list and body; everything else is untouched. */
internal fun GlobalDecl.Function.withParametersAndBody(
    newParameters: List<ParameterDecl>,
    newBody: Statement.Compound,
): GlobalDecl.Function =
    GlobalDecl.Function(
        attributes = attributes,
        name = name,
        parameters = newParameters,
        returnAttributes = returnAttributes,
        returnType = returnType,
        body = newBody,
        metadata = metadata,
    )

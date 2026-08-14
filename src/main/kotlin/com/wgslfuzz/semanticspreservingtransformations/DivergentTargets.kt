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
    // Each pair is identifierName, index of read in CURRENT compound.
    // Records reads in this compound's own statements only -- a read inside a nested scope belongs
    // to that scope's CompoundInfo.
    val reads = mutableListOf<Pair<String, Int>>()
    val childrenCompound = mutableListOf<CompoundInfo>()
    /**
     * Indices, in source order, of this compound's OWN statements that may transfer control out of
     * it -- see [escapesCompound]. A perturb/restore pair must not straddle one of these, or the
     * escape strands the restore and leaves the target perturbed.
     *
     * The walk does NOT stop at the first escape: statements past it still run when the escape does
     * not fire, so they are still analysed for reads (declarations are excluded; guarantees correctness), their nested scopes are still registered, and a pair
     * placed wholly after an escape is safe. 
     */
    val escapeIndices = mutableListOf<Int>()
}

/**
 * Every scope in the tree rooted at this one, keyed by its compound. [Statement.Compound] is a plain
 * class, so lookup is by identity -- the same assumption applyV2's `injections` map already makes.
 * Every compound reachable from the entry point body is analysed, except an injection applyV2 itself synthesised.
 */
internal fun CompoundInfo.scopesByCompound(): Map<Statement.Compound, CompoundInfo> {
    val result = mutableMapOf<Statement.Compound, CompoundInfo>()
    fun collect(info: CompoundInfo) {
        result[info.compound] = info
        info.childrenCompound.forEach(::collect)
    }
    collect(this)
    return result
}

/**
 * For v2. Walks [body], treating every Statement.Compound as a lexical scope, and finds all local
 * `var` declarations that resolve to a scalar via [firstScalarLeaf], including those in nested scopes.
 *
 * Returns the root [CompoundInfo] -- the scope tree, carrying each scope's reads, exit index and
 * child scopes -- along with the flat list of candidates found anywhere within it.
 */
internal fun findLocalVariableCandidates(
    shaderJob: ShaderJob,
    body: Statement.Compound,
): Pair<CompoundInfo, List<LocalVariableTarget>> {
    /**
     * Splits one statement into the part belonging to the enclosing scope and the parts that open a new one:
     * - [onRead] fires for every identifier read in the statement's own scope,
     * - [onNestedCompound] fires, in source order, for every compound the statement introduces -- a bare block, an if branch, a loop body, a switch clause body.
     *
     * Recursion stops at each nested compound, whose contents belong to that compound's own scope. 
     * A control-flow header is not a new scope, so reads in an `if` condition or a `for`'s init/condition/update are attributed to the enclosing scope, at the index of the control-flow statement itself.
     */
    fun splitStatement(
        node: AstNode,
        onRead: (String) -> Unit,
        onNestedCompound: (Statement.Compound) -> Unit,
    ) {
        if (node is Statement.Compound) {
            onNestedCompound(node)
            // Don't descend into the nested compound's statements: they belong to a new scope, and will be walked separately by the caller.
            return
        }
        // Only an identifier is a read.
        // Does capture reads in control-flow headers, which are part of the enclosing scope, at the index of the control-flow statement itself.
        // Eg. if (a==3i){ ... }  -- the read of `a` is attributed to the enclosing scope, at the index of the `if` statement; the body is a nested compound, and will be walked separately by the caller.
        if (node is Expression.Identifier) {
            onRead(node.name)
        }
        traverse({ child, _ -> splitStatement(child, onRead, onNestedCompound) }, node, Unit) // This traversal is limited to the starting [node] which is a statement, won't descend into siblings.
    }

    // Cannot use `traverse()` alone as we want to capture the INDEX of the line in the Statement.Compound
    fun walk(
        compoundInfo: CompoundInfo,
        inScopeVariables: MutableList<LocalVariableTarget>, // declarations visible here; truncated on scope exit
        candidates: MutableList<LocalVariableTarget>, // every candidate found anywhere; append-only
    ) {
        val inScopeOnEntry = inScopeVariables.size
        val statements = compoundInfo.compound.statements
        for (index in statements.indices) {
            val statement = statements[index] 
            val nested = mutableListOf<CompoundInfo>()
            // Current statement: collect if it is a read, and collect if it is nested compounds but only walk it, else nothing happens.
            // Eg. calling splitStatement on a `If` statement will fire onNestedCompound for the thenBranch and the elseBranch. Both would be appended to `nested`.
            splitStatement(
                statement,
                onRead = { name ->
                    // A read must name one of the declarations in scope here (include those inherited from parent scope). Parent's sibling scope's declarations are not in scope here, so they are ignored as planned. 
                    if (inScopeVariables.any { lhsBaseIdentifierName(it.target) == name }) {
                        compoundInfo.reads.add(name to index)
                    }
                },
                onNestedCompound = { nested.add(CompoundInfo(compound = it)) },
            )
            compoundInfo.childrenCompound.addAll(nested)
            nested.forEach { walk(it, inScopeVariables, candidates) } 

            // Record a statement that may leave this compound early. Statements after it are still analysed.
            if (escapesCompound(statement)) {
                compoundInfo.escapeIndices.add(index)
            }

            // Collect local variable declaration, and add it as a candidate if it resolves to a scalar.
            // Only declarations that are itself an element of the compound's OWN statements are considered.
            // Specifically. a `var` declared in a for-loop header is a Statement.Variable, but this Statement.Variable is not in compoundInfo.compound.statements[idx], so it is not considered a candidate for injection at all. TODO
            if (statement is Statement.Variable) {
                val variableType =
                    statement.typeDecl?.toType(shaderJob.environment.globalScope, shaderJob.environment) // Variable type explicitly declared
                        ?: statement.initializer?.let { shaderJob.environment.typeOf(it).asStoreTypeIfReference() } // If not, infer from initializer
                // Extract numeric scalar leaf of variable using the type
                variableType?.let { type ->
                    firstScalarLeaf(LhsExpression.Identifier(statement.name), type, 0)?.let { (target, targetType) ->
                        val candidate =
                            LocalVariableTarget(
                                declCompound = compoundInfo.compound,
                                target = target,
                                targetType = targetType,
                                declIndex = index,
                            )
                        candidates.add(candidate)
                        inScopeVariables.add(candidate)
                    }
                }
            }
        }

        // Leaving the scope/compound: its declarations are no longer visible to any sibling scope/compound.
        inScopeVariables.subList(inScopeOnEntry, inScopeVariables.size).clear() // removes all ele from inScopeOnEntry to end of list
    }

    val candidates = mutableListOf<LocalVariableTarget>()
    val rootCompoundInfo = CompoundInfo(compound = body)
    walk(rootCompoundInfo, mutableListOf(), candidates)
    // `candidates` returns the complete list of all local variables in the entry point. (unlike `inScopeVariables` which only looks at current scope and its ancestors)
    return rootCompoundInfo to candidates
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
 * True if executing [statement] may transfer control out of the compound that directly contains it,
 * so that a later statement in that same compound does not run.
 *
 * The whole subtree is examined, and each jump is attributed to the construct that owns it:
 * - Return / Discard always escape -- they leave the function outright.
 * - Break: considered an escape only up till its enclosing Loop/For/While/Switch; does not propagate outside.
 * - Continue: considered an escape only up till its enclosing Loop/For/While.
 * - The Break and Continue applicable scopes are tracked by inLoop and inSwitch flags, which are set when the walk enters a loop or switch.
 * In a switch, a `break` is captured by the switch itself -- code after the switch, within the loop that contains it, still runs. 
 * But a `continue` inside a switch will skip the code after the switch, and proceed to next iteration of the loop that contains the switch.
 */
internal fun escapesCompound(statement: Statement): Boolean {
    fun escapes(
        node: AstNode,
        inLoop: Boolean,
        inSwitch: Boolean,
    ): Boolean {
        when (node) {
            is Statement.Return, is Statement.Discard -> return true
            is Statement.Break -> if (!inLoop && !inSwitch) return true
            is Statement.Continue -> if (!inLoop) return true
            else -> {}
        }
        val isLoop = node is Statement.Loop || node is Statement.For || node is Statement.While
        // A loop nested inside a switch captures its own `break`, so `inSwitch` is not inherited past it.
        val childInSwitch = if (isLoop) false else inSwitch || node is Statement.Switch
        var found = false
        traverse(
            { child, _ ->
                if (!found && escapes(child, inLoop || isLoop, childInSwitch)) {
                    found = true
                }
            },
            node,
            Unit,
        )
        return found
    }
    return escapes(statement, inLoop = false, inSwitch = false)
}

/**
 * True if [statement] is itself an unconditional jump, so that everything after it in its compound is unreachable.
 * Differs from `escapesCompound` in that it does not look into the subtree ie. inside the statement for nested jumps, and so does not consider a `break` inside a loop to be an escape from the enclosing compound.
 */
internal fun isBareJump(statement: Statement): Boolean =
    statement is Statement.Break ||
        statement is Statement.Continue ||
        statement is Statement.Return ||
        statement is Statement.Discard

/**
 * True if [name] appears anywhere in [node]'s subtree, at any depth and including inside nested compounds,
 * as a read (Expression.Identifier) or as an assignment target (LhsExpression.Identifier). An assignment also counts as injecting a perturbation before the assignment and restoring it after the assignment, leads to modification to the variable by the specfified thread, but not by the original shader.
 *
 * `compoundInfo.reads` cannot answer this, since it stops at nested scopes and only
 * records reads.
 *
 * Deliberately conservative: a nested scope that shadows [name] with its own declaration still
 * counts, and so does a use inside a branch that never executes.
 *
 * Known gap: the match is on the identifier itself. A pointer to the target (`&x`) stored in a
 * local and dereferenced later is not recognised at the deref site -- though the address-of site
 * is, since it names the target.
 */
internal fun mentionsIdentifier(
    node: AstNode,
    name: String,
): Boolean {
    if (node is Expression.Identifier && node.name == name) {
        return true
    }
    if (node is LhsExpression.Identifier && node.name == name) {
        return true
    }
    var found = false
    traverse({ child, _ -> if (!found && mentionsIdentifier(child, name)) found = true }, node, Unit)
    return found
}

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

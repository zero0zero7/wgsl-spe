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


// ----------- CREATE NEW AST NODES -----------

// ----- Data Structs -----
/**
 * Create data structure for buffer to store thread to run, and counter value.
 */
internal fun dataStruct(id :Int =-1): GlobalDecl.Struct = GlobalDecl.Struct(
		name = if (id >= 0) "DynamicData_${id}" else "DynamicData",
		members = listOf(
			StructMember(
				name = "data",
				typeDecl = TypeDecl.I32(),
			),
		),
	)

/**
 * Create data structure for output buffer to store for multiple threads
 * Fixed-size array of [V2_OUTPUT_ARRAY_SIZE] i32 slots
 * 1 slot per thread (indexed by local_invocation_index) -- to allow multi-thread execution where every invocation needs its own slot to avoid racing on the write.
 */
internal fun multiOutputStruct(
	id :Int =-1,
	size :Int = 256,
	memberName :String ="data"): GlobalDecl.Struct = GlobalDecl.Struct(
		name =  if (id >= 0) "MultiOutput_${id}" else "MultiOutput",
		members = listOf(
			StructMember(
				name = memberName,
				typeDecl = TypeDecl.Array(
					elementType = TypeDecl.I32(),
					elementCount = Expression.IntLiteral(size.toString()),
				),
			),
		),
	)

/**
 * `local_invocation_id.x % Nu == 0u`.
 * u32 (not i32) since local_invocation_id is vec3<u32>, so `.x` is u32, and WGSL's `%` requires both operands to share that type.
 */
internal fun modNCondition(lidExpr: Expression, n: Int = 2): Expression.Binary =
	Expression.Binary(
		operator = BinaryOperator.EQUAL_EQUAL,
		lhs =
			Expression.Binary(
				operator = BinaryOperator.MODULO,
				lhs = Expression.MemberLookup(lidExpr, "x"), // cloned so that distinct instances created every time new condition is created
				rhs = Expression.IntLiteral("${n}u"),
			),
		rhs = Expression.IntLiteral("0u"),
	)

/**
 * Create <storage> input data that represents thread to run.
 */
internal fun threadToRunInputInstance(
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
 * Create <storage, read_write> output data
 */
internal fun scalarOutputInstance(
	outputBinding: String,
	structName: String,
	outputName: String
): GlobalDecl.Variable = GlobalDecl.Variable(
		attributes = listOf(
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
 * `var <counter>: i32 = 993i;`
 * To be placed first in the entry point body.
 * Non-zero so that the expected end state is a distinctive constant; see [COUNTER_INITIAL_VALUE].
 */
internal fun counterInstance(
	value :Int =COUNTER_INITIAL_VALUE,
	counterName :String): Statement =
	Statement.Variable(
		name = counterName,
		typeDecl = TypeDecl.I32(),
		initializer = Expression.IntLiteral("${value}i"),
		metadata = setOf(AddedIdentifier(counterName)),
	)

/**
 * Synthesizes a fresh entry point parameter carrying @builtin(local_invocation_id), plus an expression reading it.
 * Only called when [findExistingLID] found none, so this cannot introduce a second occurrence.
 */
internal fun lidParameter(suffix :Int =-1): Pair<ParameterDecl, Expression> {
	val paramName = if (suffix >= 0) "divergent_lid_${suffix}" else "divergent_lid"
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
 * Select only a single thread to run, early return for other threads.
 *
 * [threadSelector] reads the value from the injected input buffer (`thread_to_run.data`) rather
 * than being a literal, so the comparison cannot be constant-folded away at compile time
 * lid.x is u32, the selector is i32, so lid.x is converted to i32 to keep the comparison well-typed.
 */
internal fun singleThreadCondition(lidExpr: Expression, threadSelector: Expression, equals: Boolean): Expression =
	Expression.Binary(
		operator = if (equals) BinaryOperator.EQUAL_EQUAL else BinaryOperator.NOT_EQUAL,
		lhs = Expression.I32ValueConstructor(listOf(Expression.MemberLookup(lidExpr, "x"))),
		rhs = threadSelector.clone(),
	)

internal fun modificationStatement(
	condition: Expression,
	lhs: LhsExpression,
	newValue: Expression,
	id: Int,
): Statement.If =
	Statement.If(
		condition = condition,
		thenBranch =
			Statement.Compound(
				listOf(
					Statement.Assignment(
						lhsExpression = lhs,
						assignmentOperator = AssignmentOperator.EQUAL,
						rhs = newValue,
					),
				),
			),
	)

/**
 * `if (<indexExpr> < V2_OUTPUT_ARRAY_SIZEu) { <outputBufferName>.data[<indexExpr>] = i32(<target>); }`
 * Bounds-checked so a workgroup with more threads than [V2_OUTPUT_ARRAY_SIZE] just skips the write for its excess threads.
 * For V2
 */
internal fun indexedOutputWrite(
	outputBufferName: String,
	indexExpr: Expression,
	target: LhsExpression,
): Statement =
	Statement.If(
		condition =
			Expression.Binary(
				operator = BinaryOperator.LESS_THAN,
				lhs = indexExpr.clone(),
				rhs = Expression.IntLiteral("256u"), //${V2_OUTPUT_ARRAY_SIZE}
			),
		thenBranch =
			Statement.Compound(
				listOf(
					Statement.Assignment(
						lhsExpression =
							LhsExpression.IndexLookup(
								LhsExpression.MemberLookup(LhsExpression.Identifier(outputBufferName), 
								"data"), //V2_STRUCT_MEMBER)
								indexExpr.clone(),
							),
						assignmentOperator = AssignmentOperator.EQUAL,
						rhs = Expression.I32ValueConstructor(listOf(lhsExprToExpr(target))),
					),
				),
			),
	)
	





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


// ---------  EXISTING ----------




/**
 * Converts an LhsExpression back into a readable Expression, following the same path
 * [firstScalarLeaf] only ever builds targets out of these node kinds, so Dereference/AddressOf are unreachable here.
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

/** The variable name at the root of an lvalue chain (through member/index/paren/deref), or null. */
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
 * True if [statement] reads [name]. 
 */
internal fun statementReadsIdentifier(
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

internal fun immediateRead(
	statement: Statement,
	varName: String
): Boolean {
	return when (statement) {
		 is Statement.Variable -> statement.name == varName //TODO
		// For compound assignment (self-referential) statements, the lhs is also considered a read
		 is Statement.Assignment -> {
			 if (statement.assignmentOperator != AssignmentOperator.EQUAL) {
				 val lhsName = lhsBaseIdentifierName(statement.lhsExpression)
				 lhsName == varName
			 } else {
				 false
			 }
		 }
		else -> false
	}
}

/**
 * True if [statement] is a jump that could leave the enclosing loop/function before a later statement in the same Compound ie. scope runs (Break, Return, Discard). 
 * Continue is deliberately excluded:
 * it only skips to the next loop iteration, and the write refreshes the variable before it's read again, so a decrement stranded by a Continue is never actually observed by a stale read.
 */
internal fun isDisqualifyingExit(statement: Statement): Boolean =
	statement is Statement.Break || statement is Statement.Return || statement is Statement.Discard

internal fun collectReadIndices(
	statements: List<Statement>,
	fromIndex: Int,
	name: String,
): List<Int> {
	val reads = mutableListOf<Int>()
	for (index in (fromIndex + 1) until statements.size) {
		val statement = statements[index]
		// TODO: CHECK -- Ignore all code after an exit; unsafe to inject divergent code after exit (injected code might or might not run, non-deterministic)
		if (isDisqualifyingExit(statement)) return reads
		if (statementReadsIdentifier(statement, name)) reads.add(index)
	}
	return reads
}


// /** True if [statement] is a plain (`=`) reassignment of [name] that does NOT also read it
//  *  Excludes compound assignment, which is captured as a READ
//  */
// private fun statementReassignsIdentifier(
// 	statement: Statement,
// 	name: String,
// ): Boolean =
// 	statement is Statement.Assignment &&
// 		statement.assignmentOperator == AssignmentOperator.EQUAL &&
// 		(statement.lhsExpression as? LhsExpression.Identifier)?.name == name


/** True if [expr] contains a genuine read of `name` (an Expression.Identifier) as opposed to an Lhs value (which could be a write). */
internal fun expressionReadsIdentifier(
	expr: Expression,
	name: String,
): Boolean = nodesPreOrder(expr).any { it is Expression.Identifier && it.name == name }


internal fun zeroIndex(): Expression = Expression.IntLiteral("0i")

/**
 * Reads @group (isGroup = true) / @binding (isGroup = false) integer.
 * Returns null if the attribute is absent / not an integer literal.
 */
internal fun intAttribute(globalVar: GlobalDecl.Variable, isGroup: Boolean): Int? {
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
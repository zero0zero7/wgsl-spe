package com.wgslfuzz.core



/**
 * Parses an AstNode to return a characteristic vector
 */
private fun characteristicVectorFromTU(
    node: AstNode
): List<String> {
    val variables = mutableListOf<String>()
    traverse(::characteristicVectorFromAstnode, node, variables)
    return variables
}

private fun characteristicVectorFromAstnode(
    node: AstNode,
    variableNames: MutableList<String>,
) {
    fun addVariableName(name: String) = variableNames.add(name)

    when (node) {
        // GlobalDecl
        is GlobalDecl.Constant -> {
            addVariableName(node.name)
            node.initializer?.let{ characteristicVectorFromAstnode(it, variableNames) }
        }
        is GlobalDecl.Override -> {
            addVariableName(node.name)
            node.initializer?.let{ characteristicVectorFromAstnode(it, variableNames) }
        }
        is GlobalDecl.Variable -> {
            addVariableName(node.name)
            node.initializer?.let{ characteristicVectorFromAstnode(it, variableNames) }
        }
        is GlobalDecl.Function -> {
            for (param in node.parameters) {
                characteristicVectorFromAstnode(param, variableNames)
            }
            for (attri in node.returnAttributes) {
                characteristicVectorFromAstnode(attri, variableNames)
            }
            characteristicVectorFromAstnode(node.body, variableNames)
        }
        is GlobalDecl.ConstAssert -> characteristicVectorFromAstnode(node.expression, variableNames)

        // Expression
        is Expression.Identifier -> addVariableName(node.name)

        is Expression.Paren -> characteristicVectorFromAstnode(node.target, variableNames)
        is Expression.Unary -> characteristicVectorFromAstnode(node.target, variableNames)

        is Expression.Binary -> {
            characteristicVectorFromAstnode(node.lhs, variableNames)
            characteristicVectorFromAstnode(node.rhs, variableNames)
        }

        is Expression.FunctionCall -> node.args.forEach { characteristicVectorFromAstnode(it, variableNames) } // TODO: function call enumeration
        is Expression.ValueConstructor -> {
            if (node is Expression.ArrayValueConstructor) {
                node.elementCount?.let { characteristicVectorFromAstnode(node.elementCount, variableNames) }
            }
            node.args.forEach { characteristicVectorFromAstnode(it, variableNames) }
        }

        is Expression.MemberLookup -> {
            addVariableName(node.memberName) // TODO: check
            characteristicVectorFromAstnode(node.receiver, variableNames)
        }

        is Expression.IndexLookup -> {
            characteristicVectorFromAstnode(node.target, variableNames)
            characteristicVectorFromAstnode(node.index, variableNames)
        }

        // LhsExpression
        is LhsExpression.Identifier -> addVariableName(node.name)
        is LhsExpression.Paren -> characteristicVectorFromAstnode(
            node.target,
            variableNames
        )
        is LhsExpression.Dereference -> characteristicVectorFromAstnode(
            node.target,
            variableNames
        )
        is LhsExpression.AddressOf -> characteristicVectorFromAstnode(
            node.target,
            variableNames
        )
        is LhsExpression.MemberLookup -> {
            addVariableName(node.memberName) // TODO: check
            characteristicVectorFromAstnode(node.receiver, variableNames)
        }
        is LhsExpression.IndexLookup -> {
            characteristicVectorFromAstnode(node.target, variableNames)
            characteristicVectorFromAstnode(node.index, variableNames)
        }

        // Statement
        is Statement.Return -> {
            node.expression?.let { characteristicVectorFromAstnode(it, variableNames) }
        }

        is Statement.Assignment -> {
            node.lhsExpression?.let { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.rhs, variableNames)
        }

        is Statement.Increment -> characteristicVectorFromAstnode(node.target, variableNames)
        is Statement.Decrement -> characteristicVectorFromAstnode(node.target, variableNames)
        is Statement.ConstAssert -> characteristicVectorFromAstnode(node.expression, variableNames)
        is Statement.Compound -> node.statements.forEach { characteristicVectorFromAstnode(it, variableNames) }
        is Statement.If -> {
            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.condition, variableNames)
            characteristicVectorFromAstnode(node.thenBranch, variableNames)
            node.elseBranch?.let { characteristicVectorFromAstnode(it, variableNames) }
        }

        is Statement.Switch -> {
            node.attributesAtStart.forEach { characteristicVectorFromAstnode(it, variableNames) }
            node.attributesBeforeBody.forEach { characteristicVectorFromAstnode(it, variableNames) }
            node.clauses.forEach { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.expression, variableNames)
        }

        is Statement.Loop -> {
            node.attributesAtStart.forEach { characteristicVectorFromAstnode(it, variableNames) }
            node.attributesBeforeBody.forEach { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.body, variableNames)
            node.continuingStatement?.let { characteristicVectorFromAstnode(it, variableNames) }
        }

        is Statement.For -> {
            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
            node.init?.let { characteristicVectorFromAstnode(it, variableNames) }
            node.condition?.let { characteristicVectorFromAstnode(it, variableNames) }
            node.update?.let { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.body, variableNames)
        }

        is Statement.While -> {
            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.body, variableNames)
            characteristicVectorFromAstnode(node.condition, variableNames)
        }

        is Statement.FunctionCall -> node.args.forEach {
            characteristicVectorFromAstnode(
                it,
                variableNames
            )
        } // TODO: callee = callee function name
        is Statement.Value -> {
            addVariableName(node.name)
            characteristicVectorFromAstnode(node.initializer, variableNames)
        }

        is Statement.Variable -> {
            addVariableName(node.name)
            node.initializer?.let { characteristicVectorFromAstnode(it, variableNames) }
        }

        is ContinuingStatement -> {
            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.statements, variableNames)
            node.breakIfExpr?.let { characteristicVectorFromAstnode(it, variableNames) }
        }

        is SwitchClause -> {
            node.caseSelectors.filterNotNull().forEach {characteristicVectorFromAstnode(it, variableNames) }
            characteristicVectorFromAstnode(node.compoundStatement, variableNames)
        }

        is ParameterDecl -> {
            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
            addVariableName(node.name)
        }

//        is StructMember -> {
//            node.attributes.forEach { characteristicVectorFromAstnode(it, variableNames) }
//            addVariableName(node.name)
//        }

        // Attribute (all)
        // Directive
        // GlobalDecl: Struct, TypeAlias, Empty
        // TypeDecl (all)
        // Expression: BoolLiteral, FloatLiteral, IntLiteral
        // Statement: Empty, Break, Continue, Discard
        else -> {}
    }
}

// TODO: this wouldnt work for more than 10 variables as 2 same restrict growth strings can have different variable allocation. eg. 0112 could be 0.11.2 or 0.1.12
fun restrictGrowthStr(
    tu: TranslationUnit
) : String {
    val charVect = characteristicVectorFromTU(tu)
    return restrictGrowthStr(charVect)
}

fun restrictGrowthStr(
    charVect: List<String>
) : String {
    val result = StringBuilder()
    val map = mutableMapOf<String, Int>()
    var counter = 0
    for (varName in charVect) {
        val temp = map.getOrPut(varName) { counter++ }
        result.append(temp)
    }
    return result.toString()
}

fun scanDirectoryRGSStats(
    pathName: String,
) {
    val dir = java.io.File(pathName)
    val files = dir.listFiles { f -> f.extension == "wgsl" }?.sortedBy { it.name } ?: emptyList()

    val rgsToFiles = mutableMapOf<String, MutableList<String>>()
    for (file in files) {
        val tu = parseFromFile(file.absolutePath, LoggingParseErrorListener())
        val rgs = restrictGrowthStr(tu)
        rgsToFiles.getOrPut(rgs) { mutableListOf() }.add(file.name)
    }

    val total = files.size
    val distinctRgs = rgsToFiles.size
    val duplicateGroups = rgsToFiles.filter { it.value.size > 1 }
    val duplicateFileCount = duplicateGroups.values.sumOf { it.size }

    println("Total files: $total")
    println("Distinct RGS values: $distinctRgs")
    println("Files sharing an RGS with another: $duplicateFileCount")
    println("\nRGS -> file count (groups with duplicates):")
    duplicateGroups.entries.sortedByDescending { it.value.size }.forEach { (rgs, names) ->
        println("  \"$rgs\" -> ${names.size} files: ${names.take(5)}${if (names.size > 5) " ..." else ""}")
    }
    println("\nAll distinct RGS values:")
    rgsToFiles.keys.sorted().forEach { println("  \"$it\"") }
}


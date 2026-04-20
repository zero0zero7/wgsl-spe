/*
 * Copyright 2025 The wgsl-fuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wgslfuzz.tools

import com.wgslfuzz.core.AstNode
import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.LhsExpression
import com.wgslfuzz.core.ParameterDecl
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.StructMember
import com.wgslfuzz.core.TranslationUnit
import com.wgslfuzz.core.TypeDecl
import com.wgslfuzz.core.UniformBufferInfoByteLevel
import com.wgslfuzz.core.createShaderJob
import com.wgslfuzz.core.traverse
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Returns a short label for an AST node that includes the node type and any inline data
 * (e.g. names, literal values, operator kinds) that are useful for reading the tree.
 */
private fun nodeLabel(node: AstNode): String =
    when (node) {
        // Leaf expressions with values
        is Expression.Identifier -> "Identifier(\"${node.name}\")"
        is Expression.IntLiteral -> "IntLiteral(${node.text})"
        is Expression.FloatLiteral -> "FloatLiteral(${node.text})"
        is Expression.BoolLiteral -> "BoolLiteral(${node.text})"
        is Expression.Unary -> "Unary(${node.operator})"
        is Expression.Binary -> "Binary(${node.operator})"
        is Expression.FunctionCall -> "FunctionCall(\"${node.callee}\")"
        is Expression.MemberLookup -> "MemberLookup(.${node.memberName})"
        is Expression.ValueConstructor -> "ValueConstructor(${node.constructorName})"
        is Expression.Paren -> "Paren"
        is Expression.IndexLookup -> "IndexLookup"

        // Lhs expressions
        is LhsExpression.Identifier -> "LhsIdentifier(\"${node.name}\")"
        is LhsExpression.MemberLookup -> "LhsMemberLookup(.${node.memberName})"
        is LhsExpression.IndexLookup -> "LhsIndexLookup"
        is LhsExpression.AddressOf -> "LhsAddressOf"
        is LhsExpression.Dereference -> "LhsDereference"
        is LhsExpression.Paren -> "LhsParen"

        // Global declarations
        is GlobalDecl.Function -> "Function(\"${node.name}\")"
        is GlobalDecl.Variable -> "GlobalVariable(\"${node.name}\")"
        is GlobalDecl.Struct -> "Struct(\"${node.name}\")"
        is GlobalDecl.TypeAlias -> "TypeAlias(\"${node.name}\")"
        is GlobalDecl.Constant -> "Constant(\"${node.name}\")"
        is GlobalDecl.Override -> "Override(\"${node.name}\")"
        is GlobalDecl.ConstAssert -> "ConstAssert"
        is GlobalDecl.Empty -> "GlobalEmpty"

        // Statements
        is Statement.Compound -> "Compound"
        is Statement.If -> "If"
        is Statement.For -> "For"
        is Statement.While -> "While"
        is Statement.Loop -> "Loop"
        is Statement.Switch -> "Switch"
        is Statement.Return -> "Return"
        is Statement.Assignment -> "Assignment"
        is Statement.FunctionCall -> "StatementFunctionCall(\"${node.callee}\")"
        is Statement.Value -> "Value(\"${node.name}\")"
        is Statement.Variable -> "Variable(\"${node.name}\")"
        is Statement.Increment -> "Increment"
        is Statement.Decrement -> "Decrement"
        is Statement.Break -> "Break"
        is Statement.Continue -> "Continue"
        is Statement.Discard -> "Discard"
        is Statement.ConstAssert -> "ConstAssert"
        is Statement.Empty -> "Empty"

        // Type declarations
        is TypeDecl.NamedType -> "NamedType(\"${node.name}\")"
        is TypeDecl.ScalarTypeDecl -> "ScalarType(${node.name})"
        is TypeDecl.VectorTypeDecl -> "VectorType(${node.name})"
        is TypeDecl.MatrixTypeDecl -> "MatrixType(${node.name})"
        is TypeDecl.Array -> "Array"
        is TypeDecl.Pointer -> "Pointer(${node.addressSpace})"
        is TypeDecl.Atomic -> "Atomic"
        is TypeDecl.TextureSampled2D -> "TextureSampled2D"
        is TypeDecl.TextureSampled2DArray -> "TextureSampled2DArray"
        is TypeDecl.TextureSampled1D -> "TextureSampled1D"
        is TypeDecl.TextureSampled3D -> "TextureSampled3D"
        is TypeDecl.TextureSampledCube -> "TextureSampledCube"
        is TypeDecl.TextureSampledCubeArray -> "TextureSampledCubeArray"
        is TypeDecl.TextureMultisampled2d -> "TextureMultisampled2D"
        is TypeDecl.TextureDepth2D -> "TextureDepth2D"
        is TypeDecl.TextureDepth2DArray -> "TextureDepth2DArray"
        is TypeDecl.TextureDepthCube -> "TextureDepthCube"
        is TypeDecl.TextureDepthCubeArray -> "TextureDepthCubeArray"
        is TypeDecl.TextureDepthMultisampled2D -> "TextureDepthMultisampled2D"
        is TypeDecl.TextureExternal -> "TextureExternal"
        is TypeDecl.TextureStorage1D -> "TextureStorage1D"
        is TypeDecl.TextureStorage2D -> "TextureStorage2D"
        is TypeDecl.TextureStorage2DArray -> "TextureStorage2DArray"
        is TypeDecl.TextureStorage3D -> "TextureStorage3D"
        is TypeDecl.SamplerRegular -> "SamplerRegular"
        is TypeDecl.SamplerComparison -> "SamplerComparison"

        // Attributes
        is Attribute.Binding -> "Attr:binding"
        is Attribute.Group -> "Attr:group"
        is Attribute.Location -> "Attr:location"
        is Attribute.WorkgroupSize -> "Attr:workgroup_size"
        is Attribute.Builtin -> "Attr:builtin(${node.name})"
        is Attribute.Vertex -> "Attr:vertex"
        is Attribute.Fragment -> "Attr:fragment"
        is Attribute.Compute -> "Attr:compute"
        is Attribute.Align -> "Attr:align"
        is Attribute.Size -> "Attr:size"
        is Attribute.Interpolate -> "Attr:interpolate"
        is Attribute.Invariant -> "Attr:invariant"
        is Attribute.MustUse -> "Attr:must_use"
        is Attribute.Const -> "Attr:const"
        is Attribute.Id -> "Attr:id"
        is Attribute.BlendSrc -> "Attr:blend_src"
        is Attribute.Diagnostic -> "Attr:diagnostic"
        is Attribute.InputAttachmentIndex -> "Attr:input_attachment_index"

        // Other structural nodes
        is TranslationUnit -> "TranslationUnit"
        is ParameterDecl -> "Param(\"${node.name}\")"
        is StructMember -> "StructMember(\"${node.name}\")"
        else -> node::class.simpleName ?: "UnknownNode"
    }

private fun getChildren(node: AstNode): List<AstNode> {
    val children = mutableListOf<AstNode>()
    traverse({ child, list -> list.add(child) }, node, children)
    return children
}

private fun printTree(node: AstNode, out: PrintStream, prefix: String = "", childPrefix: String = "") {
    out.println("$prefix${nodeLabel(node)}")
    val children = getChildren(node)
    children.forEachIndexed { index, child ->
        val isLast = index == children.size - 1
        val connector = if (isLast) "└── " else "├── "
        val extension = if (isLast) "    " else "│   "
        printTree(child, out, "$childPrefix$connector", "$childPrefix$extension")
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("wgsl-fuzz AST dumper")

    val shaderPath by parser
        .option(
            ArgType.String,
            fullName = "shader",
            description = "Path to the .wgsl shader file to dump",
        ).required()

    val outputPath by parser
        .option(
            ArgType.String,
            fullName = "output",
            description = "File to write the AST dump to (defaults to stdout)",
        )

    parser.parse(args)

    val shaderFile = File(shaderPath)
    if (!shaderFile.exists()) {
        System.err.println("Shader file $shaderPath does not exist")
        exitProcess(1)
    }

    val uniformsFile = File(shaderPath.removeSuffix(".wgsl") + ".uniforms.json")
    val uniformBuffers: List<UniformBufferInfoByteLevel> =
        if (uniformsFile.exists()) {
            Json.decodeFromString(uniformsFile.readText())
        } else {
            emptyList()
        }

    val out = if (outputPath != null) PrintStream(File(outputPath!!)) else System.out

    val shaderJob = createShaderJob(shaderFile.readText(), uniformBuffers)
    printTree(shaderJob.tu, out)

    if (outputPath != null) out.close()
}
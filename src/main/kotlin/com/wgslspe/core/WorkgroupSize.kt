package com.wgslspe.core

// `@workgroupSize(N)` or  (N,N) or (N,N,N), preceded by `@compute`, followed by `fn main`.
// Matcher: locates the compute entry point.
private val WORKGROUP_SIZE_SINGLE_ARG = Regex(
    """@compute\s+@workgroup_size\(\s*(\d+)(u)?(?:\s*,\s*(\d+)(u)?(?:\s*,\s*(\d+)(u)?)?)?\s*\)\s+fn\s+main\s*\("""
)

// Only the workgroup_size attribute of that match is actually rewritten. 

private val WORKGROUP_SIZE_ATTRIBUTE = Regex("""@workgroup_size\([^)]*\)""")

/**
 * Rewrites the argument of a shader's @workgroup_size(N) / @workgroup_size(Nu) attribute via plain text substitution 
 * -- no AST/SourceSpan involved, since the literal argument to workgroup_size is never given a SourceSpan at parse time (only identifier/call-site tokens are),
 * so there is nothing for the AST-splice path (see PrintSkeletalPrograms.spliceSkeleton) to hook into for this node.
 *
 * The single-argument form is what wgslsmith output actually uses (@workgroup_size(1) / @workgroup_size(1u) in this corpus, never an explicit y or z). 
 * The two/three-argument form is accepted too and collapses to @workgroup_size(newSize), i.e. y = z = 1, so the total invocation count is exactly newSize.
 * A shader using a named override/const instead of a literal is left unchanged rather than guessing which occurrence to touch.
 *
 * Every occurrence in the text is rewritten, so a module with more than one @compute entry point gets the same size applied to all of them.
 */
fun rewriteWorkgroupSize(
    shaderText: String,
    newSize: Int,
): String {
    require(newSize >= 1) { "workgroup size must be >= 1" }
    return WORKGROUP_SIZE_SINGLE_ARG.replace(shaderText) { match ->
        // Substitute only the attribute; @compute, the whitespace between them and `fn main(` are carried through verbatim.
        val suffix = match.groupValues[2] // preserve the `u` on `@workgroup_size(1u)`
        WORKGROUP_SIZE_ATTRIBUTE.replace(match.value, "@workgroup_size($newSize$suffix)")
    }
}

package com.wgslspe.core

/**
 * AstWriter always emits a trailing comma after the last element of argument lists, vec/array constructors, switch-case selector lists, etc.
 * That is valid WGSL, but wgslsmith's (stricter) parser rejects a comma immediately before `)`, `]`, `>`, a case `:`, or -- when
 * AstWriter omits the optional colon on a case clause -- the compound statement's opening `{`.
 * Any AstWriter output headed for wgslsmith (recondition/run) must be cleaned first, or the parser panics on it.
 * WGSL has no string/char literals, so a textual pass is safe.
 *
 * NB: `}` is deliberately excluded -- wgslsmith both emits and requires the trailing comma in
 * struct bodies (`d: f32,\n}`), so it must be kept.
 */
private val TRAILING_COMMA = Regex(",\\s*(?=[)\\]>:{])")

fun stripAstWriterTrailingCommas(text: String): String = TRAILING_COMMA.replace(text, "")

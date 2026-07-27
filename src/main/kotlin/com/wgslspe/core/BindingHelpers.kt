package com.wgslspe.core

import com.wgslfuzz.core.*

/** All binding numbers currently used within [group] across every global declaration. */
fun TranslationUnit.usedBindingsForGroup(group: Int): Set<Int> =
    globalDecls
        .filterIsInstance<GlobalDecl.Variable>()
        .filter { it.groupIndex() == group }
        .mapNotNull { it.bindingIndex() }
        .toSet()

/** Largest binding number currently used in [group], or -1 if none are used. */
fun TranslationUnit.largestUsedBindingForGroup(group: Int): Int =
    usedBindingsForGroup(group).maxOrNull() ?: -1

private fun GlobalDecl.Variable.groupIndex(): Int? =
    (attributes.filterIsInstance<Attribute.Group>().firstOrNull()?.expression as? Expression.IntLiteral)?.text?.toInt()

private fun GlobalDecl.Variable.bindingIndex(): Int? =
    (attributes.filterIsInstance<Attribute.Binding>().firstOrNull()?.expression as? Expression.IntLiteral)?.text?.toInt()

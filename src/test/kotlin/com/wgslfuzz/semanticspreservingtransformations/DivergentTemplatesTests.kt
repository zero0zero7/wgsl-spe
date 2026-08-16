package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.Attribute
import com.wgslfuzz.core.AugmentedMetadata
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.GlobalDecl
import com.wgslfuzz.core.ShaderJob
import com.wgslfuzz.core.Statement
import com.wgslfuzz.core.Type
import com.wgslfuzz.core.createShaderJob
import com.wgslfuzz.core.nodesPreOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Unit tests for [PerturbationTemplate] -- the three layouts an injected perturb/restore pair can take
 * (see DivergentPerturbations.kt).
 *
 * The invariants being pinned here:
 *
 *  1. **hoisting** A snapshot or temp-copy pair declares an intermediary.
 *     That declaration must land in the ENCLOSING compound, unguarded and ahead of both guards.
 *  2. **atomic reduction** All of a pair's statements, declaration included,
 *      carry one shared [AugmentedMetadata.DeletableStatement] id, so the reducer can never strand a declaration
 *     without its uses (which would not compile) or a perturb without its restore.
 *  3. **type reach** Only f16 has no restorable template, so only f16 depends on the snapshot template
 *     being enabled. f32 is served by Negate and needs no help.
 *  4. **draw** A snapshot pair picks 60% non-restorable templates, 40% restorable.
 */
class DivergentTemplatesTests {
    // ---------- helpers ----------

    /** Used to force exactly 1 template for subsequent tests */
    private fun templateWeights(
        algebraic: Int = 0,
        snapshot: Int = 0,
        tempCopy: Int = 0,
    ) = FuzzerSettings.DivergentPerturbationWeights(
        algebraic = algebraic,
        snapshot = snapshot,
        tempCopy = tempCopy,
    )

    /**
     * Overrides the template weights in [DefaultFuzzerSettings] only the weights
     * and force injection when available.
     */
    private fun settings(
        weights: FuzzerSettings.DivergentPerturbationWeights,
        seed: Long = 1,
    ): FuzzerSettings {
        val base = DefaultFuzzerSettings(Random(seed))
        return object : FuzzerSettings by base {
            override val divergentPerturbationWeights = weights
            override fun injectDivergentCounter(): Boolean = true
        }
    }

    /** A v0-templated context: lid expr, but no injected input buffer and so no hidden constants. */
    private fun bareContext(): EntryPointContext =
        EntryPointContext(
            lidExpr = Expression.Identifier("lid"),
            parameters = emptyList(),
        )

    /** One whole segment from `let a` to `let c` since no mention of `var t`. */
    private val singleIntLocal =
        """
        @compute @workgroup_size(1)
        fn main() {
          var t: i32 = 7i;
          let a = 1i;
          let b = 2i;
          let c = 3i;
        }
        """.trimIndent()

    private fun inject(
        shaderText: String,
        weights: FuzzerSettings.DivergentPerturbationWeights,
        seed: Long = 1,
    ): Statement.Compound {
        val shaderJob = createShaderJob(shaderText, emptyList())
        val transformed = requireNotNull(applyV2(shaderJob, settings(weights, seed))) { "no injection happened" }
        return entryPointBody(transformed)
    }

    private fun entryPointBody(shaderJob: ShaderJob): Statement.Compound =
        shaderJob.tu.globalDecls
            .filterIsInstance<GlobalDecl.Function>()
            .first { fn -> fn.attributes.any { it is Attribute.Compute } }
            .body

    private fun deletableId(statement: Statement): Int? =
        statement.metadata
            .filterIsInstance<AugmentedMetadata.DeletableStatement>()
            .firstOrNull()
            ?.id

    /** Indices, within [compound]'s own statements, that carry a DeletableStatement id. */
    private fun injectedIndices(compound: Statement.Compound): List<Int> =
        compound.statements.indices.filter { deletableId(compound.statements[it]) != null }

    private fun assignedName(statement: Statement): String? =
        ((statement as? Statement.Assignment)?.rhs as? Expression.Identifier)?.name

    /** The le statement inside an `if (guard) { ... }` */
    private fun soleGuardedStatement(statement: Statement): Statement? =
        (statement as? Statement.If)?.thenBranch?.statements?.singleOrNull()

    // ---------- hoisting ----------

    @Test
    fun `a snapshot declaration hoisted into the enclosing compound ahead of both guards`() {
        val body = inject(singleIntLocal, templateWeights(snapshot = 1))
        val indices = injectedIndices(body)
        assertEquals(3, indices.size, "snapshot contributes a declaration plus two guards")

        val declaration = body.statements[indices[0]]
        assertTrue(declaration is Statement.Value, "the temporary is a `let`, declared at compound level")
        assertTrue(
            body.statements[indices[1]] is Statement.If && body.statements[indices[2]] is Statement.If,
            "both halves are guarded",
        )
        assertTrue(indices[0] < indices[1] && indices[0] < indices[2], "declaration must preced both guards")
    }

    @Test
    fun `a snapshot restore reads the name its declaration binds`() {
        val body = inject(singleIntLocal, templateWeights(snapshot = 1))
        val indices = injectedIndices(body)
        val declaredName = (body.statements[indices[0]] as Statement.Value).name
        val restore = requireNotNull(soleGuardedStatement(body.statements[indices[2]]))

        assertEquals(declaredName, assignedName(restore), "restore must read the snapshot declaration name")
        assertTrue(declaredName.startsWith("injected_"))
    }

    @Test
    fun `a temp-copy restore assigns through the intermediary var and then to the target`() {
        val body = inject(singleIntLocal, templateWeights(tempCopy = 1))
        val indices = injectedIndices(body)

        val declaration = body.statements[indices[0]]
        assertTrue(declaration is Statement.Variable, "the intermediary is a `var`, so it can be assigned")
        val intermediaryName = (declaration as Statement.Variable).name

        val restoreBody = (body.statements[indices[2]] as Statement.If).thenBranch.statements
        assertEquals(2, restoreBody.size, "temp-copy restores in two steps: `intermediary = restore(t); t = intermediary;`")
        assertEquals(intermediaryName, lhsBaseIdentifierName((restoreBody[0] as Statement.Assignment).lhsExpression))
        assertEquals(intermediaryName, assignedName(restoreBody[1]))
    }

    @Test
    fun `an algebraic pair contributes exactly two statements and no temporary`() {
        val body = inject(singleIntLocal, templateWeights(algebraic = 1))
        val indices = injectedIndices(body)

        assertEquals(2, indices.size)
        assertTrue(indices.all { body.statements[it] is Statement.If })
    }

    // ---------- atomic reduction ----------

    @Test
    fun `every statement of a pair shares one deletable id, whatever the template`() {
        for (weights in listOf(templateWeights(algebraic = 1), templateWeights(snapshot = 1), templateWeights(tempCopy = 1))) {
            val body = inject(singleIntLocal, weights)
            val ids = injectedIndices(body).map { deletableId(body.statements[it]) }

            assertTrue(ids.isNotEmpty())
            assertEquals(1, ids.distinct().size, "a pair must reduce atomically, declaration included")
        }
    }

    @Test
    fun `reducing a snapshot pair removes its declaration too`() {
        val shaderJob = createShaderJob(singleIntLocal, emptyList())
        val injected = requireNotNull(applyV2(shaderJob, settings(templateWeights(snapshot = 1))))
        val reduced = requireNotNull(injected.reduce { true }).first

        val survivingTemporaries =
            nodesPreOrder(reduced.tu).filter { node ->
                (node as? Statement.Value)?.name?.startsWith("injected_") == true ||
                    (node as? Statement.Variable)?.name?.startsWith("injected_") == true
            }
        assertTrue(survivingTemporaries.isEmpty(), "the temporary must not outlive the pair that reads it")
    }

    // ---------- type reach ----------

    @Test
    fun `f16 needs the snapshot template, and f32 does not`() {
        val context = bareContext()

        // f32 is served by Negate, so it has never depended on the snapshot template.
        assertNotNull(chooseRestorablePerturbation(settings(templateWeights(algebraic = 1)), context, Type.F32))
        assertNotNull(chooseTemplate(settings(templateWeights(algebraic = 1)), context, Type.F32))

        // f16 has no restorable template at all.
        assertNull(chooseRestorablePerturbation(settings(templateWeights(algebraic = 1)), context, Type.F16))
        assertNull(chooseTemplate(settings(templateWeights(algebraic = 1)), context, Type.F16))
        assertNotNull(chooseTemplate(settings(templateWeights(snapshot = 1)), context, Type.F16))
    }

    // ---------- the 70/30 draw ----------

    @Test
    fun `a snapshot pair draws about 70 percent non-restorable templates`() {
        val context = bareContext()
        val trials = 4000
        val restorable =
            (1..trials).count { seed ->
                val settings = settings(FuzzerSettings.DivergentPerturbationWeights(), seed.toLong())
                choosePerturbation(settings, context, Type.I32) is RestorablePerturbation
            }

        val restorableFraction = restorable.toDouble() / trials
        assertTrue(
            restorableFraction in 0.26..0.34,
            "expected ~0.30 restorable, got $restorableFraction",
        )
    }
}

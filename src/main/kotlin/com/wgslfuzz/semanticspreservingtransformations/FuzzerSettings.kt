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

package com.wgslfuzz.semanticspreservingtransformations

import com.wgslfuzz.core.BinaryOperator
import com.wgslfuzz.core.Expression
import com.wgslfuzz.core.Metadata
import java.util.Random
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log

interface FuzzerSettings {
    fun goDeeper(currentDepth: Int): Boolean = randomDouble() < 4.0 / (currentDepth.toDouble() + 2.0) && currentDepth < 18

    // Get a unique identifiers for transformation such as `ControlFlowWrapper`
    fun getUniqueId(): Int

    // Yields a random integer in the range [0, limit)
    fun randomInt(limit: Int): Int

    // Yields a random integer in the range [from, until). Routed through randomInt so that
    // implementations only ever have to supply one source of randomness.
    fun randomInt(
        from: Int,
        until: Int,
    ): Int {
        require(until > from) { "Empty range [$from, $until)" }
        return from + randomInt(until - from)
    }

    // Yield a random double in the range [0, 1]
    fun randomDouble(): Double

    fun randomBool(): Boolean

    fun <T> randomElement(list: List<T>): T {
        require(list.isNotEmpty()) { "Cannot get random element of an empty list" }
        return list[randomInt(list.size)]
    }

    fun <T> randomElement(vararg elements: T): T = randomElement(elements.toList())

    data class FalseByConstructionWeights(
        val plainFalse: (depth: Int) -> Int = { 1 },
        val falseAndArbitrary: (depth: Int) -> Int = { 3 },
        val arbitraryAndFalse: (depth: Int) -> Int = { 3 },
        val notTrue: (depth: Int) -> Int = { 3 },
        val opaqueFalseFromUniformValues: (depth: Int) -> Int = { 6 },
    )

    data class TrueByConstructionWeights(
        val plainTrue: (depth: Int) -> Int = { 1 },
        val trueOrArbitrary: (depth: Int) -> Int = { 3 },
        val arbitraryOrTrue: (depth: Int) -> Int = { 3 },
        val notFalse: (depth: Int) -> Int = { 3 },
        val opaqueTrueFromUniformValues: (depth: Int) -> Int = { 6 },
    )

    val falseByConstructionWeights: FalseByConstructionWeights
        get() = FalseByConstructionWeights()

    val trueByConstructionWeights: TrueByConstructionWeights
        get() = TrueByConstructionWeights()

    data class ScalarIdentityOperationWeights(
        val addZeroLeft: Int = 1,
        val addZeroRight: Int = 1,
        val subZero: Int = 2,
        val mulOneLeft: Int = 1,
        val mulOneRight: Int = 1,
        val divOne: Int = 2,
    )

    val scalarIdentityOperationWeights: ScalarIdentityOperationWeights
        get() = ScalarIdentityOperationWeights()

    // Which guard shape wraps an injected perturb/restore pair. See DivergentConditions.kt.
    data class DivergentConditionWeights(
        val negatedModulus: Int = 2,
        val moduloVersusMask: Int = 3,
        val bitTest: Int = 3,
        val threshold: Int = 1,
        // Dropped when the entry point has no runtime-opaque selector to compare against (v0).
        val selectorEquality: Int = 2,
        // Same, and additionally needs threadToRun() to have two divisors above 1.
        val divisorPair: Int = 8,
    )

    val divergentConditionWeights: DivergentConditionWeights
        get() = DivergentConditionWeights()

    data class DivergentPerturbationWeights(
        val restorable: Int = 30,
        val nonRestorable: Int = 70,
        // --- restorable transformations: used by algebraic and temp-copy ---
        val addSub: Int = 1,
        val negate: Int = 1, // i32 always; u32 only when the hidden zero is available ie. v1/v2 not v0.
        // --- templates ---
        val algebraic: Int = 2, // takes on addSub or negate
        val tempCopy: Int = 1, // perturnation to target must be restorable, done via an intermediary temp variable
        val snapshot: Int = 1, // perturbation to target can be non-restorable
    )

    val divergentPerturbationWeights: DivergentPerturbationWeights
        get() = DivergentPerturbationWeights()

    data class DeadBreaksAndContinuesWeights(
        val ifFalse: Int = 1,
        val ifTrue: Int = 1,
    )

    val deadBreaksAndContinuesWeights: DeadBreaksAndContinuesWeights
        get() = DeadBreaksAndContinuesWeights()

    data class DeadDiscardOrReturnWeights(
        val ifFalse: Int = 2,
        val ifTrue: Int = 2,
        val whileFalse: Int = 1,
        val forLoopWithFalseCondition: Int = 1,
        val loopWithUnconditionalBreak: Int = 1,
    )

    val deadDiscardOrReturnWeights: DeadDiscardOrReturnWeights
        get() = DeadDiscardOrReturnWeights()

    data class KnownValueWeights(
        val plainKnownValue: (depth: Int) -> Int = { 1 },
        val sumOfKnownValues: (depth: Int) -> Int = { 2 },
        val differenceOfKnownValues: (depth: Int) -> Int = { 2 },
        val productOfKnownValues: (depth: Int) -> Int = { 2 },
        val knownValueDerivedFromUniform: (depth: Int) -> Int = { 6 },
    )

    val knownValueWeights: KnownValueWeights
        get() = KnownValueWeights()

    data class ControlFlowWrappingWeights(
        val ifTrueWrapping: (depth: Int) -> Int = { 1 },
        val ifFalseWrapping: (depth: Int) -> Int = { 1 },
        val singleIterForLoop: (depth: Int) -> Int = { 1 },
        val singleIterLoop: (depth: Int) -> Int = { 1 },
        val singleIterWhileLoop: (depth: Int) -> Int = { 1 },
        val switchCase: (depth: Int) -> Int = { 1 },
    ) {
        class SwitchCaseProbabilities {
            // TODO(https://github.com/mc-imperial/wgsl-fuzz/issues/249) Find better probability distributions
            fun numberOfCases(fuzzerSettings: FuzzerSettings): Int = ((-ln(fuzzerSettings.randomDouble())) * 50).toInt()

            fun numberOfCasesInAClause(fuzzerSettings: FuzzerSettings): Int = ceil(log(fuzzerSettings.randomDouble(), 0.5)).toInt()
        }

        val switchCaseProbabilities: SwitchCaseProbabilities
            get() = SwitchCaseProbabilities()
    }

    val controlFlowWrappingWeights: ControlFlowWrappingWeights
        get() = ControlFlowWrappingWeights()

    class ArbitraryBooleanExpressionWeights(
        val not: (depth: Int) -> Int = { 1 },
        val or: (depth: Int) -> Int = { 2 },
        val and: (depth: Int) -> Int = { 2 },
        val lessThan: (depth: Int) -> Int = { 1 },
        val greaterThan: (depth: Int) -> Int = { 1 },
        val lessThanOrEqual: (depth: Int) -> Int = { 1 },
        val greaterThanOrEqual: (depth: Int) -> Int = { 1 },
        val equal: (depth: Int) -> Int = { 1 },
        val notEqual: (depth: Int) -> Int = { 1 },
        val variableFromScope: (depth: Int) -> Int = { 1 },
        val literal: (depth: Int) -> Int = { 1 },
    )

    val arbitraryBooleanExpressionWeights: ArbitraryBooleanExpressionWeights
        get() = ArbitraryBooleanExpressionWeights()

    data class ArbitraryIntExpressionWeights(
        val swapIntType: (depth: Int) -> Int = { 1 },
        val binaryOr: (depth: Int) -> Int = { 1 },
        val binaryAnd: (depth: Int) -> Int = { 1 },
        val binaryXor: (depth: Int) -> Int = { 1 },
        val negate: (depth: Int) -> Int = { 1 },
        val addition: (depth: Int) -> Int = { 1 },
        val subtraction: (depth: Int) -> Int = { 1 },
        val multiplication: (depth: Int) -> Int = { 1 },
        val division: (depth: Int) -> Int = { 1 },
        val modulo: (depth: Int) -> Int = { 1 },
        val abs: (depth: Int) -> Int = { 1 },
        val clamp: (depth: Int) -> Int = { 1 },
        val countLeadingZeros: (depth: Int) -> Int = { 1 },
        val countOneBits: (depth: Int) -> Int = { 1 },
        val countTrailingZeros: (depth: Int) -> Int = { 1 },
        val dot4U8Packed: (depth: Int) -> Int = { 1 },
        val dot4I8Packed: (depth: Int) -> Int = { 1 },
        val extractBits: (depth: Int) -> Int = { 1 },
        val firstLeadingBit: (depth: Int) -> Int = { 1 },
        val firstTrailingBit: (depth: Int) -> Int = { 1 },
        val insertBits: (depth: Int) -> Int = { 1 },
        val max: (depth: Int) -> Int = { 1 },
        val min: (depth: Int) -> Int = { 1 },
        val reverseBits: (depth: Int) -> Int = { 1 },
        val sign: (depth: Int) -> Int = { 1 },
        val variableFromScope: (depth: Int) -> Int = { 1 },
        val literal: (depth: Int) -> Int = { 1 },
    )

    val arbitraryIntExpressionWeights: ArbitraryIntExpressionWeights
        get() = ArbitraryIntExpressionWeights()

    data class ArbitraryElseBranchWeights(
        val empty: (depth: Int) -> Int = { 1 },
        val ifStatement: (depth: Int) -> Int = { 1 },
        val compound: (depth: Int) -> Int = { 1 },
    )

    val arbitraryElseBranchWeights: ArbitraryElseBranchWeights
        get() = ArbitraryElseBranchWeights()

    val randomArbitraryCompoundLength: (depth: Int) -> Int
        get() = { randomInt(10) }

    fun injectDeadBreak(): Boolean = randomInt(100) < 50

    fun injectDeadContinue(): Boolean = randomInt(100) < 50

    fun injectDeadDiscard(): Boolean = randomInt(100) < 50

    fun injectDeadReturn(): Boolean = randomInt(100) < 50

    fun applyIdentityOperation(): Boolean = randomInt(100) < 50

    fun controlFlowWrap(): Boolean = randomInt(100) < 50

    fun injectDivergentCounter(): Boolean = randomInt(100) < 50

    /**
     * The local_invocation_id.x value that v1/v2's single-thread gate admits.
     *
     * The injected input buffer is populated with this same value by the tooling, so the two MUST
     * agree -- see ApplyDivergentInjections' --threadToRun. 
     */
    fun threadToRun(): Int = DEFAULT_THREAD_TO_RUN

    /**
     * Whether a Negate perturbation spells its two halves differently (`-x` then `zero - x`)
     * rather than identically (`-x` twice). Identical is the default; distinct is harder for a
     * compiler to cancel by syntactic matching.
     */
    fun negateWithDistinctSpellings(): Boolean = false
}

// Seed used when a caller does not supply its own generator, so that an unseeded
// DefaultFuzzerSettings is still reproducible rather than varying run to run.
const val DEFAULT_FUZZER_SEED: Long = 42

// Default thread admitted by v1/v2's single-thread gate -- 60 
const val DEFAULT_THREAD_TO_RUN: Int = 60

class DefaultFuzzerSettings(
    private val generator: Random = Random(DEFAULT_FUZZER_SEED),
) : FuzzerSettings {
    private var nextId: Int = 0

    override fun getUniqueId(): Int {
        nextId++
        return nextId
    }

    override fun randomInt(limit: Int): Int = generator.nextInt(limit)

    override fun randomDouble(): Double = generator.nextDouble()

    override fun randomBool(): Boolean = generator.nextBoolean()
}

fun <T> choose(
    fuzzerSettings: FuzzerSettings,
    choices: List<Pair<Int, () -> T>>,
): T {
    val functions = mutableListOf<() -> T>()
    for (choice in choices) {
        (0..<choice.first).forEach { _ ->
            functions.add(choice.second)
        }
    }
    return fuzzerSettings.randomElement(functions)()
}

fun binaryExpressionRandomOperandOrder(
    fuzzerSettings: FuzzerSettings,
    operator: BinaryOperator,
    operand1: Expression,
    operand2: Expression,
    metadata: Set<Metadata>,
): Expression =
    if (fuzzerSettings.randomBool()) {
        Expression.Binary(operator, operand1, operand2, metadata)
    } else {
        Expression.Binary(operator, operand2, operand1, metadata)
    }

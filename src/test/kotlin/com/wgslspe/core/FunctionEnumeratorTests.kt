package com.wgslspe.core

import com.wgslfuzz.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionEnumeratorTests {

    private fun parseAndResolve(src: String): Pair<TranslationUnit, ResolvedEnvironment> {
        val tu = parseFromString(src, LoggingParseErrorListener())
        return tu to resolve(tu)
    }

    // Enumerates all skeletons and, for each call site (in collection order), returns the set of
    // callee names seen across all characteristic vectors — i.e. the offered replacement options.
    private fun optionsPerSite(src: String): List<Set<String>> {
        val (tu, env) = parseAndResolve(src)
        val charVects = getFunctionSkeletons(tu, env, random = false).map { it.second }.toList()
        if (charVects.isEmpty()) return emptyList()
        return (0 until charVects[0].size).map { i -> charVects.map { it[i] }.toSet() }
    }

    // -------------------------------------------------------------------------
    // collectCallCandidates — builtins outside swap groups are not candidates
    // -------------------------------------------------------------------------

    @Test
    fun builtinsOutsideSwapGroupsAreNotCandidates() {
        // dot is vector-only/shape-changing and belongs to no swap group.
        val src = """
            fn f() -> f32 {
              return dot(vec2(1.0, 2.0), vec2(3.0, 4.0));
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val (userCalls, builtinCalls) = collectCallCandidates(tu, env)
        assertTrue(userCalls.isEmpty(), "Builtin calls must not be user candidates")
        assertTrue(builtinCalls.isEmpty(), "Builtins outside swap groups must not be candidates")
        assertEquals(0, getFunctionSkeletons(tu, env, random = false).count())
    }

    @Test
    fun userCallsAreCollectedWithSignature() {
        val src = """
            fn helper(a: i32) -> i32 {
              return a;
            }
            fn f() -> i32 {
              return helper(1i);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val (candidates, builtinCalls) = collectCallCandidates(tu, env)
        assertEquals(1, candidates.size)
        assertEquals("f", candidates[0].enclosingFunction.name)
        assertEquals(FunctionType(listOf(Type.I32), Type.I32), candidates[0].calleeType)
        assertTrue(builtinCalls.isEmpty())
    }

    // -------------------------------------------------------------------------
    // suitableFunctions — signature must match exactly
    // -------------------------------------------------------------------------

    @Test
    fun replacementRequiresIdenticalSignature() {
        val src = """
            fn x1(a: i32) -> i32 {
              return a;
            }
            fn x2(a: f32) -> f32 {
              return a;
            }
            fn x3(a: i32) -> i32 {
              return a + 1i;
            }
            fn caller() -> i32 {
              return x3(5i);
            }
        """.trimIndent()
        assertEquals(listOf(setOf("x1", "x3")), optionsPerSite(src))
    }

    // -------------------------------------------------------------------------
    // suitableFunctions — recursion (direct and indirect) is prevented
    // -------------------------------------------------------------------------

    @Test
    fun selfAndCycleFormingReplacementsExcluded() {
        // Call site inside b (callee a). Replacing with b itself would be direct recursion;
        // replacing with c would create the cycle b -> c -> b. Only a is a valid option, so the
        // only skeleton is the identity one.
        val src = """
            fn a(x: i32) -> i32 {
              return x;
            }
            fn b(x: i32) -> i32 {
              return a(x);
            }
            fn c(x: i32) -> i32 {
              return b(x);
            }
        """.trimIndent()
        // Sites in collection order: the call in b, then the call in c.
        // Call in c (callee b) may also choose a (earlier in topo order) but not b's caller c.
        assertEquals(listOf(setOf("a"), setOf("a", "b")), optionsPerSite(src))
    }

    @Test
    fun topoOrderBeatsSourceOrder() {
        // d is declared after the caller but calls nothing, so it sits early in the topological
        // order of the call graph and is a legal (acyclic) replacement despite its position.
        val src = """
            fn a(x: i32) -> i32 {
              return x;
            }
            fn caller(x: i32) -> i32 {
              return a(x);
            }
            fn d(x: i32) -> i32 {
              return x + 1i;
            }
        """.trimIndent()
        assertEquals(listOf(setOf("a", "d")), optionsPerSite(src))
    }

    // -------------------------------------------------------------------------
    // suitableFunctions — entry points and @must_use
    // -------------------------------------------------------------------------

    @Test
    fun entryPointsAreNotReplacementCandidates() {
        val src = """
            @compute @workgroup_size(1)
            fn entry() {
            }
            fn a() {
            }
            fn caller() {
              a();
            }
        """.trimIndent()
        assertEquals(listOf(setOf("a")), optionsPerSite(src))
    }

    @Test
    fun mustUseExcludedOnlyForStatementCalls() {
        val src = """
            @must_use
            fn m() -> i32 {
              return 1i;
            }
            fn f() -> i32 {
              return 2i;
            }
            fn statementCaller() {
              f();
            }
            fn expressionCaller() -> i32 {
              return f();
            }
        """.trimIndent()
        // Site order: statement call in statementCaller, then expression call in expressionCaller.
        assertEquals(listOf(setOf("f"), setOf("m", "f")), optionsPerSite(src))
    }

    // -------------------------------------------------------------------------
    // getFunctionSkeletons — nested call replacements compose
    // -------------------------------------------------------------------------

    @Test
    fun nestedCallsAreReplacedTogether() {
        val src = """
            fn inner1() -> i32 {
              return 1i;
            }
            fn inner2() -> i32 {
              return 2i;
            }
            fn outer1(x: i32) -> i32 {
              return x;
            }
            fn outer2(x: i32) -> i32 {
              return x + 1i;
            }
            fn caller() -> i32 {
              return outer2(inner2());
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val skeletons = getFunctionSkeletons(tu, env, random = false).toList()
        // Two sites (outer call then its nested argument call), two options each.
        assertEquals(4, skeletons.size)

        // The combination replacing both callees at once must rewrite the nested call too —
        // a wholesale splice of the outer replacement would silently keep inner2.
        val bothReplaced = skeletons.single { it.second == listOf("outer1", "inner1") }.first
        val expected = parseFromString(
            src.replace("outer2(inner2())", "outer1(inner1())"),
            LoggingParseErrorListener(),
        )
        assertTrue(
            equalTu(bothReplaced, listOf(expected)) == 0,
            "Nested replacement was lost or misapplied",
        )
    }

    // -------------------------------------------------------------------------
    // Builtin swap groups
    // -------------------------------------------------------------------------

    @Test
    fun builtinSwapGroupOffersWholeGroup() {
        val src = """
            fn f(x: i32) -> i32 {
              return countOneBits(x);
            }
        """.trimIndent()
        val expectedGroup = setOf(
            "countLeadingZeros", "countOneBits", "countTrailingZeros",
            "firstLeadingBit", "firstTrailingBit", "reverseBits",
        )
        assertEquals(listOf(expectedGroup), optionsPerSite(src))
    }

    @Test
    fun builtinSwapAppliesToTheCall() {
        // The argument is a literal, not an identifier: deepEquals compares SourceSpan metadata,
        // and identifiers downstream of a different-length callee would carry shifted spans in
        // the re-parsed expected program. Literals carry no spans.
        val src = """
            fn f() -> u32 {
              return reverseBits(7u);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val swapped = getFunctionSkeletons(tu, env, random = false)
            .single { it.second == listOf("countOneBits") }
            .first
        val expected = parseFromString(
            src.replace("reverseBits(7u)", "countOneBits(7u)"),
            LoggingParseErrorListener(),
        )
        assertTrue(equalTu(swapped, listOf(expected)) == 0, "Builtin swap was misapplied")
    }

    @Test
    fun shadowedBuiltinNamesAreNotOffered() {
        // A user function named `cos` shadows the builtin, so swapping sin -> cos would resolve
        // to the user function (whose signature need not match). `cos` must not be offered;
        // unshadowed group members like `tan` must be.
        val src = """
            fn cos(x: f32) -> f32 {
              return x;
            }
            fn f(x: f32) -> f32 {
              return sin(x);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val (userCalls, builtinCalls) = collectCallCandidates(tu, env)
        assertTrue(userCalls.isEmpty())
        assertEquals(1, builtinCalls.size)
        val options = optionsPerSite(src).single()
        assertTrue("cos" !in options, "Shadowed builtin name must not be offered: $options")
        assertTrue("sin" in options && "tan" in options, "Unshadowed group members expected: $options")
    }

    @Test
    fun partialDomainBuiltinsAreNotOfferedAsReplacements() {
        // cos is total; partial-domain group members (acosh needs x >= 1, etc.) must not be
        // swapped in, or the variant could be indeterminate on inputs where the original was not.
        val src = """
            fn f(x: f32) -> f32 {
              return cos(x);
            }
        """.trimIndent()
        val options = optionsPerSite(src).single()
        assertTrue("cos" in options && "sin" in options && "floor" in options, "Total members expected: $options")
        val partial = setOf("asin", "acos", "acosh", "atanh", "log", "log2", "sqrt", "inverseSqrt")
        assertTrue(options.none { it in partial }, "Partial-domain members must not be offered: $options")
    }

    @Test
    fun overflowProneBuiltinsAreNotOfferedAtConstArgumentSites() {
        // The argument is a literal vector, so a swapped-in call would be const-evaluated at
        // shader-creation time; overflow there is a mandatory shader-creation error (spec 15.7.2).
        // trunc(960f) is fine, sinh(960f) is inf.
        val src = """
            fn f() -> f32 {
              return trunc(vec3(-140.0, 960.0, -272.0)).x;
            }
        """.trimIndent()
        val options = optionsPerSite(src).single()
        assertTrue("trunc" in options && "floor" in options && "sin" in options, "Bounded members expected: $options")
        val overflowProne = setOf("sinh", "cosh", "exp", "exp2", "tan", "degrees")
        assertTrue(options.none { it in overflowProne }, "Overflow-prone members must not be offered at const sites: $options")
    }

    @Test
    fun overflowProneBuiltinsAreOfferedAtRuntimeArgumentSites() {
        // One identifier resolving to a `let` makes the argument a runtime expression, so no
        // const-evaluation happens and overflow-prone members are legal swap-ins. A `const`
        // local keeps the argument const-evaluable, so the site stays restricted.
        val src = """
            fn f() -> f32 {
              let a = 960.0;
              const b = 960.0;
              return trunc(vec3(-140.0, a, -272.0)).x + trunc(vec3(-140.0, b, -272.0)).x;
            }
        """.trimIndent()
        val (letSite, constSite) = optionsPerSite(src)
        assertTrue("sinh" in letSite && "cosh" in letSite, "let argument is runtime, expected overflow-prone members: $letSite")
        assertTrue("sinh" !in constSite && "cosh" !in constSite, "const argument stays const-evaluable: $constSite")
    }

    @Test
    fun overflowProneCalleeKeepsIdentityAtConstSites() {
        // Directionality mirrors unsafeAsReplacement: a program that already contains an
        // overflow-prone call on const arguments (necessarily non-overflowing, or it would not
        // compile) keeps its identity option.
        val src = """
            fn f() -> f32 {
              return exp(vec3(-140.0, 2.0, -272.0)).x;
            }
        """.trimIndent()
        val options = optionsPerSite(src).single()
        assertTrue("exp" in options, "Identity must remain an option: $options")
        assertTrue("sinh" !in options, "Other overflow-prone members must still be excluded: $options")
    }

    @Test
    fun partialDomainCalleeKeepsIdentityAndTotalReplacements() {
        // Directionality: the fma site may not receive smoothstep (partial: equal edges), while
        // the smoothstep site may receive fma (total) and always keeps its identity option.
        val src = """
            fn f(a: f32, b: f32, c: f32) -> f32 {
              return fma(a, b, c) + smoothstep(a, b, c);
            }
        """.trimIndent()
        assertEquals(listOf(setOf("fma"), setOf("smoothstep", "fma")), optionsPerSite(src))
    }

    @Test
    fun builtinCallsInGlobalConstInitializersAreCandidates() {
        // Builtin swaps are legal outside function bodies: all group members are @const.
        val src = """
            const c: f32 = max(1.0, 2.0);
            fn f() -> f32 {
              return c;
            }
        """.trimIndent()
        assertEquals(listOf(setOf("min", "max")), optionsPerSite(src))
    }

    @Test
    fun userAndBuiltinSitesCombine() {
        // One user site (callee b, options {a, b}) and one builtin site nested in its argument
        // (callee countOneBits, 6 options) -> 12 combinations, user site first in the vector.
        val src = """
            fn a(x: i32) -> i32 {
              return x;
            }
            fn b(x: i32) -> i32 {
              return x + 1i;
            }
            fn caller() -> i32 {
              return b(countOneBits(7i));
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val skeletons = getFunctionSkeletons(tu, env, random = false).toList()
        assertEquals(12, skeletons.size)

        val bothReplaced = skeletons.single { it.second == listOf("a", "reverseBits") }.first
        val expected = parseFromString(
            src.replace("b(countOneBits(7i))", "a(reverseBits(7i))"),
            LoggingParseErrorListener(),
        )
        assertTrue(
            equalTu(bothReplaced, listOf(expected)) == 0,
            "Combined user+builtin replacement was misapplied",
        )
    }

    @Test
    fun identityCombinationIsIncluded() {
        val src = """
            fn a(x: i32) -> i32 {
              return x;
            }
            fn b(x: i32) -> i32 {
              return x + 1i;
            }
            fn caller() -> i32 {
              return b(3i);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val skeletons = getFunctionSkeletons(tu, env, random = false).toList()
        assertEquals(2, skeletons.size)
        val identity = skeletons.single { it.second == listOf("b") }.first
        assertTrue(equalTu(identity, listOf(tu)) == 0, "Identity combination should reproduce the input")
    }

    @Test
    fun callNodesCarryCalleeSourceSpans() {
        // Splice-based consumers (spliceSkeleton) replace exactly the bytes of the callee token,
        // so both call node kinds must carry a SourceSpan covering the callee name and nothing else.
        val src = """
            fn g() {
            }
            fn f(x: f32) -> f32 {
              g();
              return sin(x);
            }
        """.trimIndent()
        val (tu, env) = parseAndResolve(src)
        val (userCalls, builtinCalls) = collectCallCandidates(tu, env)
        assertEquals(1, userCalls.size)
        assertEquals(1, builtinCalls.size)
        val expectedCallees = mapOf<AstNode, String>(userCalls[0].call to "g", builtinCalls[0].call to "sin")
        for ((node, callee) in expectedCallees) {
            val span = node.metadata.filterIsInstance<SourceSpan>().single()
            assertEquals(callee, src.substring(span.start, span.stopInclusive + 1), "Span must cover exactly the callee token")
        }
    }
}

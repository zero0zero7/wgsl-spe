package com.wgslspe.core

import com.wgslfuzz.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlphaEquivalenceTests {

    // -------------------------------------------------------------------------
    // restrictGrowthStr — direct unit tests
    // -------------------------------------------------------------------------

    @Test
    fun emptyVectorGivesEmptyString() {
        assertEquals("", restrictGrowthStr(emptyList()))
    }

    @Test
    fun singleElementGivesZero() {
        assertEquals("0", restrictGrowthStr(listOf("a")))
    }

    @Test
    fun allSameElementsGiveAllZeros() {
        assertEquals("000", restrictGrowthStr(listOf("a", "a", "a")))
    }

    @Test
    fun allDistinctElementsGiveSequentialIntegers() {
        assertEquals("012", restrictGrowthStr(listOf("a", "b", "c")))
    }

    @Test
    fun repeatedSecondElementPattern() {
        // a, b, b -> 0, 1, 1
        assertEquals("011", restrictGrowthStr(listOf("a", "b", "b")))
    }

    @Test
    fun repeatedFirstElementPattern() {
        // a, a, b -> 0, 0, 1
        assertEquals("001", restrictGrowthStr(listOf("a", "a", "b")))
    }

    @Test
    fun ababPattern() {
        // a, b, a, b, c, b -> 0, 1, 0, 1, 2, 1
        assertEquals("010121", restrictGrowthStr(listOf("a", "b", "a", "b", "c", "b")))
    }

    @Test
    fun abaPattern() {
        // a, b, a -> 0, 1, 0
        assertEquals("010", restrictGrowthStr(listOf("a", "b", "a")))
    }

    // -------------------------------------------------------------------------
    // restrictGrowthStr — alpha-equivalence: renaming variables gives same RGS
    // -------------------------------------------------------------------------

    @Test
    fun renamedVariablesGiveSameRGS() {
        // ["a", "b", "a"] and ["b", "a", "b"] differ only in variable names -> same RGS
        val rgs1 = restrictGrowthStr(listOf("a", "b", "a"))
        val rgs2 = restrictGrowthStr(listOf("b", "a", "b"))
        assertEquals(rgs1, rgs2)
    }

    @Test
    fun orderOfFirstOccurrenceDeterminesId() {
        // ["b", "a"] -> b gets 0, a gets 1 -> "01"
        // ["a", "b"] -> a gets 0, b gets 1 -> "01"
        // Both represent "two distinct variables used in order" -> same RGS
        val rgs1 = restrictGrowthStr(listOf("a", "b"))
        val rgs2 = restrictGrowthStr(listOf("b", "a"))
        assertEquals(rgs1, rgs2)
    }

    @Test
    fun aabAndbbaAreAlphaEquivalent() {
        // ["a", "a", "b"] and ["b", "b", "a"] represent the same usage pattern
        val rgs1 = restrictGrowthStr(listOf("a", "a", "b"))
        val rgs2 = restrictGrowthStr(listOf("b", "b", "a"))
        assertEquals(rgs1, rgs2)
    }

    @Test
    fun aabAndabbAreNotAlphaEquivalent() {
        // ["a", "a", "b"] (001) vs ["a", "b", "b"] (011)
        val rgs1 = restrictGrowthStr(listOf("a", "a", "b"))
        val rgs2 = restrictGrowthStr(listOf("a", "b", "b"))
        assertNotEquals(rgs1, rgs2)
    }

    // -------------------------------------------------------------------------
    // characteristicVectorFromAstnode -- direct Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun literalOnlyProgramHasEmptyRGS() {
        val src = """
            fn f() -> i32 {
              return 1i;
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        val rgs = restrictGrowthStr(tu)
        assertEquals("", rgs, "Program without variables must always produce RGS '', got $rgs")
    }

    @Test
    fun rgsCapturesFuncParams() {
        val src = """
            fn f(a: array<vec4<f32>>, b: i32) -> f32 {
              return a[b][b];
            }
        """.trimIndent() // ["a", "b", "a", "b", "b"]
        val tu = parseFromString(src, LoggingParseErrorListener())
        assertEquals(restrictGrowthStr(tu), "01011")
    }

    @Test
    fun rgsCapturesNestedVar() {
        val src = """
            fn f() {
              let c : i32 = a[a[b][b]][b];
            }
        """.trimIndent()
        val tu = parseFromString(src, LoggingParseErrorListener())
        assertEquals(restrictGrowthStr(tu), "011222")
    }

    // From paper, Figure 6
    @Test
    fun alphaEquivalentProgramsHaveSameRGS() {
        val src1 = """
            fn f() -> i32 {
              var c : i32 = 1i;
              var b : i32 = 0i;
              if (c) {
                var a : i32 = 3i;
                var d : i32 = 5i;
                b = a + d;
               }
              return c + b + 0;
            }
        """.trimIndent()
        val tu1 = parseFromString(src1, LoggingParseErrorListener())
        val src2 = """
            fn f() -> i32 {
              var b : i32 = 1i;
              var a : i32 = 0i;
              if (b) {
                var d : i32 = 3i;
                var c : i32 = 5i;
                a = d + c;
               }
              return b + a + 0;
            }
        """.trimIndent()
        val tu2 = parseFromString(src2, LoggingParseErrorListener())
        assertEquals(restrictGrowthStr(tu1), restrictGrowthStr(tu2))
    }

    // -------------------------------------------------------------------------
    // Integrate with SkeletalEnumerator
    // -------------------------------------------------------------------------

    @Test
    fun fourDistinctSkeletonsProduceFourDistinctRGS() {
        // fn f(a: i32, b: i32) -> i32 { return a + b; }
        // skeletal enumerator only enumerates through variable usages, not decls
        // charVects: ["a", "b", "a", "a"], ["a", "b", "a","b"], ["a", "b", "b","b"], ["a", "b", "b","a"]
        // RGS:        "0100"      "0101"       "0111"        "0110"
        val src = """
            fn f(a: i32, b: i32) -> i32 {
              return a + b;
            }
        """.trimIndent()
        val tu_src = parseFromString(src, LoggingParseErrorListener())
        val env = resolve(tu_src)
        val rgsValues = allReplacementSkeletons(tu_src, env).map { (tu, _) -> restrictGrowthStr(tu) }.toList()
        assertEquals(4, rgsValues.size, "Expected 4 distinct RGS")
        assertEquals(setOf("0100", "0101", "0111", "0110"), rgsValues.toSet())
    }


    @Test
    fun f1() {
        val dir = java.io.File("/Users/limxinyi/Desktop/Masters/Imperial/thesis/wgsl-spe/out/ex2")
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
}

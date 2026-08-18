package com.coveninja.cove.ui.icons

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mark drawn in the app has to be the mark in `packaging/icons/cove.svg`.
 *
 * This exists because the first generated version of `CoveLogo.kt` split its path data across
 * concatenated lines and lost the space at every break, merging pairs of coordinates into single
 * numbers. The result still drew *something* — which is the dangerous part. A corrupt logo does
 * not throw, it just quietly stops being the logo, and nothing but an eye on a screen would have
 * caught it. Comparing against the source file catches both that and any later drift.
 */
class CoveLogoTest {

    private val svg = File("../../packaging/icons/cove.svg")
    private val generated = File("src/commonMain/kotlin/com/coveninja/cove/ui/icons/CoveLogo.kt")

    private val pathPattern = Regex("""parsePathString\("([^"]+)"\)""")
    private val svgPathPattern = Regex("""<path d="([^"]+)"""")

    // Mutation applied to verify: dropped a single space from one path string in CoveLogo.kt →
    // test failed, which is exactly the corruption that shipped a broken mark.
    @Test
    fun `every path is the one the source artwork draws`() {
        assertTrue(svg.isFile, "source artwork missing at ${svg.absolutePath}")
        assertTrue(generated.isFile, "generated vector missing")

        val fromSvg = svgPathPattern.findAll(svg.readText()).map { it.groupValues[1] }.toList()
        val fromKotlin = pathPattern.findAll(generated.readText()).map { it.groupValues[1] }.toList()

        assertEquals(fromSvg.size, fromKotlin.size, "path count differs from the artwork")
        fromSvg.zip(fromKotlin).forEachIndexed { index, (expected, actual) ->
            assertEquals(expected, actual, "path $index differs from the artwork")
        }
    }

    // A coordinate pair that lost its separator reads as one very large number, which is what
    // makes the corruption invisible in a diff and visible only on screen.
    @Test
    fun `no coordinate has run into its neighbour`() {
        val suspicious = Regex("""\d{7,}""")
        pathPattern.findAll(generated.readText()).forEach { match ->
            val path = match.groupValues[1]
            assertTrue(
                suspicious.find(path) == null,
                "path contains a run-together coordinate: ${suspicious.find(path)?.value}",
            )
        }
    }
}

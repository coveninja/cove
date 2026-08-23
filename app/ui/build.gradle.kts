import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.tasks.UntrackedTask
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm("desktop")
    android {
        namespace = "com.coveninja.cove.ui"
        compileSdk = 37
        minSdk = 28
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            // materialIconsExtended was used only by the old iconify shim (com.ongshok.iconify).
            // That shim is replaced by CoveIcons.kt + IconifyIcon.kt which need no Material icons.

            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        // Presentation state (PlaybackSession in particular) carries real decision
        // logic that no other module's suite reaches.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.runtime)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

// ── Icon pipeline ─────────────────────────────────────────────────────────────
//
// Icons are real Iconify artwork baked in at build time: `generateIcons` fetches the
// SVGs and writes CoveIcons.kt; `verifyIcons` fails the build if source uses an icon
// that was never generated. See docs/APP.md.
//
// Everything below lives in a top-level object and typed task classes rather than in
// `doLast { }` blocks calling script-level helpers. A lambda that reaches back into the
// build script captures the script object, which the configuration cache cannot
// serialize — that made `./gradlew hotRun` fail on the second run with
// "cannot deserialize Gradle script object references".

/**
 * Pure helpers, deliberately outside the build script's own scope. A top-level object
 * compiles to a standalone class, so task actions calling into it hold no script reference.
 */
object IconGen {

    /** Matches quoted icon-name literals in Kotlin source, e.g. "lucide:x". */
    val ICON_RE = Regex(""""([a-z0-9]+(?:-[a-z0-9]+)*:[a-z0-9]+(?:-[a-z0-9]+)*)"""")

    data class IconPathSpec(
        val pathData: String,
        val fill: String?,
        val stroke: String?,
        val strokeWidth: Float,
        val strokeCap: String,
        val strokeJoin: String,
        val fillEvenOdd: Boolean,
    )

    fun scanIcons(files: Iterable<File>, extra: List<String>): Set<String> {
        val names = mutableSetOf<String>()
        files.filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            ICON_RE.findAll(f.readText()).forEach { m -> names.add(m.groupValues[1]) }
        }
        names.addAll(extra)
        return names
    }

    // ── SVG → path data ───────────────────────────────────────────────────────

    private fun Double.fmtNum(): String =
        if (this % 1.0 == 0.0) this.toLong().toString() else "%.6g".format(this)

    /** A circle becomes two semicircular arcs so the path closes correctly. */
    fun circleToPath(cx: Double, cy: Double, r: Double): String {
        val left  = (cx - r).fmtNum()
        val right = (cx + r).fmtNum()
        val cyS   = cy.fmtNum()
        val rS    = r.fmtNum()
        return "M $left $cyS A $rS $rS 0 1 0 $right $cyS A $rS $rS 0 1 0 $left $cyS Z"
    }

    /** A rect becomes lines plus corner arcs, honouring SVG's rx ≤ w/2, rx ≤ h/2 clamp. */
    fun rectToPath(x: Double, y: Double, w: Double, h: Double, rx: Double): String {
        val r = rx.coerceAtMost(w / 2).coerceAtMost(h / 2)
        if (r <= 0.0) {
            val x0 = x.fmtNum();       val y0 = y.fmtNum()
            val x1 = (x + w).fmtNum(); val y1 = (y + h).fmtNum()
            return "M $x0 $y0 H $x1 V $y1 H $x0 Z"
        }
        val rS     = r.fmtNum()
        val x0     = x.fmtNum();             val y0     = y.fmtNum()
        val x1     = (x + w).fmtNum();       val y1     = (y + h).fmtNum()
        val xStart = (x + r).fmtNum();       val xEnd   = (x + w - r).fmtNum()
        val yStart = (y + r).fmtNum();       val yEnd   = (y + h - r).fmtNum()
        return "M $xStart $y0 H $xEnd A $rS $rS 0 0 1 $x1 $yStart " +
               "V $yEnd A $rS $rS 0 0 1 $xEnd $y1 " +
               "H $xStart A $rS $rS 0 0 1 $x0 $yEnd " +
               "V $yStart A $rS $rS 0 0 1 $xStart $y0 Z"
    }

    private fun mergeAttrs(inherited: Map<String, String>, el: Element): Map<String, String> {
        val result = inherited.toMutableMap()
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            result[a.nodeName] = a.nodeValue
        }
        return result
    }

    /**
     * The runtime only understands `none`, `currentColor`, and `#rgb` hex literals; anything
     * else resolves to null and the path would render invisibly. Reject it here so an
     * unsupported paint fails at generation time rather than silently dropping geometry.
     * This also catches SVG's implicit "absent fill means black" default, which for a
     * tintable Iconify glyph is nearly always a mistake rather than a literal black.
     */
    private fun validatePaint(value: String, kind: String, iconName: String): String {
        if (value == "none" || value == "currentColor" ||
            Regex("^#[0-9a-fA-F]{3,8}$").matches(value)
        ) {
            return value
        }
        throw GradleException(
            "'$iconName': unsupported $kind paint \"$value\". " +
            "Only none, currentColor, and #hex are supported — an absent fill hits SVG's black " +
            "default and lands here too. Handle it in build.gradle.kts if the collection needs it."
        )
    }

    private fun buildIconPath(d: String, attrs: Map<String, String>, iconName: String): IconPathSpec {
        fun attr(key: String): String? = attrs[key]?.ifEmpty { null }
        // SVG's default fill is black; surfaced explicitly so validatePaint can reject it.
        val fill        = validatePaint(attr("fill") ?: "black", "fill", iconName)
        val stroke      = attr("stroke")?.let { validatePaint(it, "stroke", iconName) }
        val strokeWidth = attr("stroke-width")?.toFloatOrNull() ?: 1f
        val strokeCap   = attr("stroke-linecap") ?: "butt"
        val strokeJoin  = attr("stroke-linejoin") ?: "miter"
        // fill-rule and clip-rule both affect the fill algorithm; evenodd wins if either says so.
        val fillEvenOdd = attr("fill-rule") == "evenodd" || attr("clip-rule") == "evenodd"
        return IconPathSpec(
            pathData    = d,
            fill        = if (fill   == "none") null else fill,
            stroke      = if (stroke == null || stroke == "none") null else stroke,
            strokeWidth = strokeWidth,
            strokeCap   = strokeCap,
            strokeJoin  = strokeJoin,
            fillEvenOdd = fillEvenOdd,
        )
    }

    /**
     * Walks one SVG element, propagating [inherited] attributes to children. Only the
     * elements these collections actually emit are handled; anything else throws so a new
     * collection fails loudly at generation time rather than silently losing geometry.
     */
    private fun collectPaths(
        el:        Element,
        inherited: Map<String, String>,
        out:       MutableList<IconPathSpec>,
        iconName:  String,
    ) {
        fun recurseInto(attrs: Map<String, String>) {
            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    collectPaths(child as Element, attrs, out, iconName)
                }
            }
        }

        when (val tag = el.tagName.lowercase()) {
            // Synthetic wrapper added when parsing the body fragment.
            "root" -> recurseInto(inherited)
            // A <g> inherits its parent's attrs; children then inherit the merged set.
            "g"    -> recurseInto(mergeAttrs(inherited, el))
            "path" -> {
                val d = el.getAttribute("d").ifBlank {
                    throw GradleException("'$iconName': <path> element has no d attribute")
                }
                out.add(buildIconPath(d, mergeAttrs(inherited, el), iconName))
            }
            "circle" -> {
                val cx = el.getAttribute("cx").toDoubleOrNull() ?: 0.0
                val cy = el.getAttribute("cy").toDoubleOrNull() ?: 0.0
                val r  = el.getAttribute("r").toDoubleOrNull()  ?: 0.0
                out.add(buildIconPath(circleToPath(cx, cy, r), mergeAttrs(inherited, el), iconName))
            }
            "rect" -> {
                val x  = el.getAttribute("x").toDoubleOrNull()      ?: 0.0
                val y  = el.getAttribute("y").toDoubleOrNull()      ?: 0.0
                val w  = el.getAttribute("width").toDoubleOrNull()  ?: 0.0
                val h  = el.getAttribute("height").toDoubleOrNull() ?: 0.0
                val rx = el.getAttribute("rx").toDoubleOrNull()     ?: 0.0
                out.add(buildIconPath(rectToPath(x, y, w, h, rx), mergeAttrs(inherited, el), iconName))
            }
            else -> throw GradleException(
                "'$iconName': unsupported SVG element <$tag>. " +
                "Add a handler in build.gradle.kts, or choose a different collection."
            )
        }
    }

    fun parseSvgBody(body: String, iconName: String): List<IconPathSpec> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setErrorHandler(null)   // suppress DOCTYPE warnings for SVG fragments
        val doc = builder.parse(InputSource(StringReader("<root>$body</root>")))
        val out = mutableListOf<IconPathSpec>()
        collectPaths(doc.documentElement, emptyMap(), out, iconName)
        return out
    }

    // ── Kotlin source generation ──────────────────────────────────────────────

    private fun Float.fmtF(): String = if (this % 1f == 0f) "${this.toInt()}f" else "${this}f"
    private fun String.ktStr(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

    fun renderSource(
        allDims:  Map<String, Pair<Float, Float>>,
        allPaths: Map<String, List<IconPathSpec>>,
    ): String = buildString {
        appendLine("// Generated by ./gradlew generateIcons — do not hand-edit.")
        appendLine("package com.coveninja.cove.ui.icons")
        appendLine()
        appendLine("internal data class IconPath(")
        appendLine("""    val pathData: String,""")
        appendLine("""    val fill: String?,        // null = "none"; "currentColor"; or a hex literal""")
        appendLine("""    val stroke: String?,""")
        appendLine("""    val strokeWidth: Float,""")
        appendLine("""    val strokeCap: String,    // "butt" | "round" | "square"""")
        appendLine("""    val strokeJoin: String,   // "miter" | "round" | "bevel"""")
        appendLine("""    val fillEvenOdd: Boolean,""")
        appendLine(")")
        appendLine("internal data class IconSpec(val width: Float, val height: Float, val paths: List<IconPath>)")
        appendLine()
        appendLine("internal val CoveIcons: Map<String, IconSpec> = mapOf(")
        for ((name, wh) in allDims.entries.sortedBy { it.key }) {
            val paths = allPaths[name] ?: continue
            val (w, h) = wh
            appendLine("    ${name.ktStr()} to IconSpec(${w.fmtF()}, ${h.fmtF()}, listOf(")
            for (p in paths) {
                appendLine("        IconPath(")
                appendLine("            pathData    = ${p.pathData.ktStr()},")
                appendLine("            fill        = ${if (p.fill   == null) "null" else p.fill.ktStr()},")
                appendLine("            stroke      = ${if (p.stroke == null) "null" else p.stroke.ktStr()},")
                appendLine("            strokeWidth = ${p.strokeWidth.fmtF()},")
                appendLine("            strokeCap   = ${p.strokeCap.ktStr()},")
                appendLine("            strokeJoin  = ${p.strokeJoin.ktStr()},")
                appendLine("            fillEvenOdd = ${p.fillEvenOdd},")
                appendLine("        ),")
            }
            appendLine("    )),")
        }
        appendLine(")")
    }
}

/** Fetches icon SVGs from the Iconify API and rewrites CoveIcons.kt. Requires network. */
// Untracked, not merely uncacheable: the output depends on a remote service, so an
// explicit run must always re-fetch rather than being skipped as UP-TO-DATE.
@UntrackedTask(because = "Output depends on the Iconify API, not just on local inputs")
abstract class GenerateIconsTask : DefaultTask() {

    @get:InputFiles
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val extraIcons: ListProperty<String>

    @get:OutputFile
    abstract val generatedFile: RegularFileProperty

    @TaskAction
    fun run() {
        val iconNames = IconGen.scanIcons(sourceFiles.files, extraIcons.get())
        logger.lifecycle("generateIcons: found ${iconNames.size} icon names")

        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val slurper = JsonSlurper()

        val allDims  = mutableMapOf<String, Pair<Float, Float>>()
        val allPaths = mutableMapOf<String, List<IconGen.IconPathSpec>>()

        for ((prefix, names) in iconNames.groupBy { it.substringBefore(':') }.entries.sortedBy { it.key }) {
            val requested = names.map { it.substringAfter(':') }.sorted()
            val url = "https://api.iconify.design/$prefix.json?icons=${requested.joinToString(",")}"
            logger.lifecycle("  GET $url")

            val resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (resp.statusCode() != 200) {
                throw GradleException(
                    "Iconify returned HTTP ${resp.statusCode()} for prefix '$prefix'. " +
                    "Check your network and the collection name."
                )
            }

            val root = slurper.parseText(resp.body()) as Map<*, *>

            val notFound = (root["not_found"] as? List<*>)?.map { it.toString() }.orEmpty()
            if (notFound.isNotEmpty()) {
                throw GradleException(
                    "Iconify does not have these icons in '$prefix': ${notFound.joinToString(", ")}. " +
                    "Check the spelling against icon-sets.iconify.design."
                )
            }

            val collW = (root["width"]  as? Number)?.toFloat() ?: 24f
            val collH = (root["height"] as? Number)?.toFloat() ?: 24f

            val aliases = (root["aliases"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            val icons   = root["icons"] as Map<*, *>

            for (reqName in requested) {
                val fullName = "$prefix:$reqName"

                // Aliases redirect a requested name to the canonical parent entry. The map
                // key stays the requested name so callers use the name they typed.
                val resolvedName = (aliases[reqName] as? Map<*, *>)?.get("parent")?.toString() ?: reqName

                val iconData = icons[resolvedName] as? Map<*, *>
                    ?: throw GradleException(
                        "'$fullName' resolved to '$resolvedName' but that key was not in the response."
                    )
                val body = iconData["body"]?.toString()
                    ?: throw GradleException("'$fullName': Iconify response has no body field")

                allDims[fullName]  = Pair(
                    (iconData["width"]  as? Number)?.toFloat() ?: collW,
                    (iconData["height"] as? Number)?.toFloat() ?: collH,
                )
                allPaths[fullName] = IconGen.parseSvgBody(body, fullName)
                logger.lifecycle("    $fullName → $resolvedName (${allPaths[fullName]!!.size} paths)")
            }
        }

        val out = generatedFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(IconGen.renderSource(allDims, allPaths))
        logger.lifecycle("generateIcons: wrote ${allDims.size} icons (${out.length()} bytes) to ${out.name}")
    }
}

/** Fails the build if source uses an icon that is absent from CoveIcons.kt. No network. */
abstract class VerifyIconsTask : DefaultTask() {

    @get:InputFiles
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val extraIcons: ListProperty<String>

    // InputFiles rather than InputFile: the latter fails with Gradle's generic
    // "specified file does not exist" before the task body runs, losing the actionable
    // "run ./gradlew generateIcons" message for a missing or deleted CoveIcons.kt.
    @get:InputFiles
    abstract val generatedFile: RegularFileProperty

    // A verification task needs an output for Gradle to mark it UP-TO-DATE; without one it
    // would re-scan on every compile, including every hot-reload cycle.
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @TaskAction
    fun run() {
        val file = generatedFile.get().asFile
        if (!file.exists()) {
            throw GradleException("CoveIcons.kt does not exist — run: ./gradlew generateIcons")
        }

        val used = IconGen.scanIcons(sourceFiles.files, extraIcons.get())
        val generated = file.readLines()
            .mapNotNull { IconGen.ICON_RE.find(it)?.groupValues?.get(1) }
            .toSet()

        val missing = used - generated
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Icons used in source are missing from CoveIcons.kt — run: ./gradlew generateIcons\n" +
                missing.sorted().joinToString("\n") { "  $it" }
            )
        }
        logger.lifecycle("verifyIcons: all ${used.size} icons present in CoveIcons.kt ✓")
        stampFile.get().asFile.also { it.parentFile.mkdirs() }.writeText("${used.size}\n")
    }
}

// Icon names assembled dynamically in source won't be caught by the regex scan.
// List them here so generateIcons includes them and verifyIcons doesn't reject them.
val extraIconNames = listOf<String>()

val generatedIconsFile = layout.projectDirectory
    .file("src/commonMain/kotlin/com/coveninja/cove/ui/icons/CoveIcons.kt")

// The generated table is excluded from the scan so its own map keys aren't read as usages.
val iconSourceFiles = fileTree("src") {
    include("**/*.kt")
    exclude("**/icons/CoveIcons.kt")
}

val generateIcons = tasks.register<GenerateIconsTask>("generateIcons") {
    group       = "codegen"
    description = "Fetch real icon SVGs from the Iconify API and regenerate CoveIcons.kt."
    sourceFiles.from(iconSourceFiles)
    extraIcons.set(extraIconNames)
    generatedFile.set(generatedIconsFile)
}

val verifyIcons = tasks.register<VerifyIconsTask>("verifyIcons") {
    group       = "verification"
    description = "Fail if any icon used in source is absent from CoveIcons.kt."
    sourceFiles.from(iconSourceFiles)
    extraIcons.set(extraIconNames)
    generatedFile.set(generatedIconsFile)
    stampFile.set(layout.buildDirectory.file("icons/verify.stamp"))
}

// A missing icon must be a build error, never a silent wrong glyph at runtime.
tasks.matching { it.name.startsWith("compile") && "Kotlin" in it.name }
    .configureEach { dependsOn(verifyIcons) }

import { readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "svelte/compiler";
import ts from "typescript";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const webDir = resolve(scriptDir, "..");
const repoDir = resolve(webDir, "..");
const sourceDir = join(webDir, "src");
const messagesDir = join(webDir, "messages");
const readmePath = join(repoDir, "README.md");
const writeReadme = process.argv.includes("--write-readme");

const visibleAttributes = new Set([
  "alt",
  "aria-label",
  "aria-description",
  "body",
  "confirmLabel",
  "description",
  "emptyText",
  "header",
  "heading",
  "label",
  "loadingMessage",
  "message",
  "placeholder",
  "text",
  "title",
  "tooltip",
]);
const visibleProperties = new Set([
  "description",
  "header",
  "label",
  "placeholder",
  "title",
]);
const technicalText = new Set([
  "Cove",
  "DV",
  "HDR",
  "TMDB",
]);

async function sourceFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "paraglide") continue;
      files.push(...(await sourceFiles(path)));
    } else if (
      [".svelte", ".ts"].includes(extname(entry.name)) &&
      !entry.name.includes(".test.")
    ) {
      files.push(path);
    }
  }
  return files;
}

function isVisibleText(value) {
  const normalized = value.replace(/\s+/g, " ").trim();
  if (!normalized || !/\p{L}/u.test(normalized)) return false;
  if (technicalText.has(normalized)) return false;
  if (/^https?:\/\//i.test(normalized)) return false;
  if (/^#\s/.test(normalized)) return false;
  if (/^(?:GB|MB|(?:·\s*)?OpenSubtitles)$/.test(normalized)) return false;
  if (/^(?:·\s*)?(?:Space|[A-Z](?:\s+[A-Z])*)$/.test(normalized)) {
    return false;
  }
  if (/^[±+−-]?\d+(?:s|m|h)$/.test(normalized)) return false;
  if (/^translate(?:X|Y)?\(/.test(normalized)) return false;
  if (normalized === "s" || normalized === "m") return false;
  return true;
}

function outputLiterals(expression) {
  if (!expression || typeof expression !== "object") return [];
  if (
    expression.type === "Literal" &&
    typeof expression.value === "string"
  ) {
    return [{ value: expression.value, node: expression }];
  }
  if (expression.type === "TemplateLiteral") {
    return expression.quasis.map((quasi) => ({
      value: quasi.value?.cooked ?? quasi.value?.raw ?? "",
      node: quasi,
    }));
  }
  if (expression.type === "ConditionalExpression") {
    return [
      ...outputLiterals(expression.consequent),
      ...outputLiterals(expression.alternate),
    ];
  }
  if (expression.type === "LogicalExpression") {
    return outputLiterals(expression.right);
  }
  if (
    expression.type === "TSAsExpression" ||
    expression.type === "TSNonNullExpression" ||
    expression.type === "ChainExpression"
  ) {
    return outputLiterals(expression.expression);
  }
  return [];
}

function propertyName(property) {
  if (!property || property.computed) return "";
  if (property.key?.type === "Identifier") return property.key.name;
  if (property.key?.type === "Literal") return String(property.key.value);
  return "";
}

function lineNumber(source, offset) {
  return source.slice(0, offset).split("\n").length;
}

function finding(file, source, kind, value, node) {
  return {
    file: relative(webDir, file),
    line: lineNumber(source, node.start ?? 0),
    kind,
    value: value.replace(/\s+/g, " ").trim(),
  };
}

function scanSvelte(file, source) {
  const ast = parse(source, { filename: file });
  const findings = [];

  function add(kind, value, node) {
    if (isVisibleText(value)) {
      findings.push(finding(file, source, kind, value, node));
    }
  }

  function scanHtml(node) {
    if (!node || typeof node !== "object") return;
    if (node.type === "Text") {
      add("text", node.data ?? node.raw ?? "", node);
      return;
    }
    if (node.type === "Attribute") {
      if (visibleAttributes.has(node.name)) {
        for (const value of node.value ?? []) {
          if (value.type === "Text") {
            add(node.name, value.data ?? value.raw ?? "", value);
          } else if (value.type === "MustacheTag") {
            for (const literal of outputLiterals(value.expression)) {
              add(node.name, literal.value, literal.node);
            }
          }
        }
      }
      return;
    }
    if (
      node.type === "MustacheTag" ||
      node.type === "RawMustacheTag"
    ) {
      for (const literal of outputLiterals(node.expression)) {
        add("expression", literal.value, literal.node);
      }
      return;
    }
    if (node.type === "RenderTag") {
      for (const argument of node.expression?.arguments ?? []) {
        for (const literal of outputLiterals(argument)) {
          add("render argument", literal.value, literal.node);
        }
      }
      return;
    }

    for (const [key, value] of Object.entries(node)) {
      if (
        ["expression", "loc", "metadata", "start", "end", "type"].includes(
          key,
        )
      ) {
        continue;
      }
      if (Array.isArray(value)) value.forEach(scanHtml);
      else scanHtml(value);
    }
  }

  function scanScript(node) {
    if (!node || typeof node !== "object") return;
    if (
      node.type === "Property" &&
      visibleProperties.has(propertyName(node))
    ) {
      for (const literal of outputLiterals(node.value)) {
        add(`script ${propertyName(node)}`, literal.value, literal.node);
      }
    }
    for (const [key, value] of Object.entries(node)) {
      if (["loc", "start", "end"].includes(key)) continue;
      if (Array.isArray(value)) value.forEach(scanScript);
      else scanScript(value);
    }
  }

  scanHtml(ast.html);
  scanScript(ast.instance);
  scanScript(ast.module);
  return findings;
}

function scanTypeScript(file, source) {
  const sourceFile = ts.createSourceFile(
    file,
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  );
  const findings = [];

  function literals(expression) {
    if (ts.isStringLiteralLike(expression)) {
      return [{ value: expression.text, node: expression }];
    }
    if (ts.isTemplateExpression(expression)) {
      return [
        { value: expression.head.text, node: expression.head },
        ...expression.templateSpans.map((span) => ({
          value: span.literal.text,
          node: span.literal,
        })),
      ];
    }
    if (ts.isConditionalExpression(expression)) {
      return [
        ...literals(expression.whenTrue),
        ...literals(expression.whenFalse),
      ];
    }
    if (
      ts.isParenthesizedExpression(expression) ||
      ts.isAsExpression(expression) ||
      ts.isNonNullExpression(expression)
    ) {
      return literals(expression.expression);
    }
    return [];
  }

  function visit(node) {
    if (
      ts.isPropertyAssignment(node) &&
      visibleProperties.has(node.name.getText(sourceFile).replaceAll('"', ""))
    ) {
      for (const literal of literals(node.initializer)) {
        if (isVisibleText(literal.value)) {
          findings.push(
            finding(file, source, "TypeScript property", literal.value, {
              start: literal.node.getStart(sourceFile),
            }),
          );
        }
      }
    }
    ts.forEachChild(node, visit);
  }

  visit(sourceFile);
  return findings;
}

function messageKeys(catalog) {
  return Object.keys(catalog).filter((key) => key !== "$schema");
}

function placeholders(message) {
  return [
    ...String(message).matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g),
  ]
    .map((match) => match[1])
    .sort();
}

function percent(numerator, denominator) {
  return denominator === 0
    ? "100.0"
    : ((numerator / denominator) * 100).toFixed(1);
}

function coverageBlock(rows, localizedCallsites, findings) {
  const sourceTotal = localizedCallsites + findings.length;
  const sourcePercent = percent(localizedCallsites, sourceTotal);
  return [
    "<!-- i18n-coverage:start -->",
    "<!-- Generated by web/scripts/i18n-audit.mjs; do not edit by hand. -->",
    "| Language / check | Coverage |",
    "| --- | ---: |",
    ...rows.map(
      (row) =>
        `| ${row.label} (\`${row.locale}\`) | ${row.percent}% (${row.translated}/${row.total} messages) |`,
    ),
    `| UI source audit | ${sourcePercent}% (${localizedCallsites} localized call sites, ${findings.length} unlocalized literals) |`,
    "<!-- i18n-coverage:end -->",
  ].join("\n");
}

async function updateReadme(block) {
  const readme = await readFile(readmePath, "utf8");
  const markerPattern =
    /<!-- i18n-coverage:start -->[\s\S]*?<!-- i18n-coverage:end -->/;
  let next;
  if (markerPattern.test(readme)) {
    next = readme.replace(markerPattern, block);
  } else {
    const section = [
      "## Localization",
      "",
      "Cove checks message-catalog completeness and scans Svelte UI source for visible, untranslated literals. The source percentage is a static guard rather than a substitute for review; brand names, media-format abbreviations, and URL examples are intentionally excluded.",
      "",
      block,
      "",
      "This table is generated by `cd web && npm run i18n:coverage` and verified by CI. `npm run check` fails when catalogs, placeholders, or visible UI literals are invalid.",
      "",
    ].join("\n");
    next = readme.replace("## Install\n", `${section}\n## Install\n`);
  }
  if (next !== readme) await writeFile(readmePath, next);
}

const files = await sourceFiles(sourceDir);
const svelteFiles = files.filter((file) => extname(file) === ".svelte");
const sources = await Promise.all(
  files.map(async (file) => ({ file, source: await readFile(file, "utf8") })),
);
const findings = [];
for (const { file, source } of sources) {
  if (extname(file) === ".svelte") {
    try {
      findings.push(...scanSvelte(file, source));
    } catch (error) {
      findings.push({
        file: relative(webDir, file),
        line: 1,
        kind: "parse error",
        value: error instanceof Error ? error.message : String(error),
      });
    }
  } else if (extname(file) === ".ts") {
    findings.push(...scanTypeScript(file, source));
  }
}

const localizedCallsites = sources.reduce(
  (total, { source }) =>
    total + [...source.matchAll(/\b(?:m|msg)\.[A-Za-z0-9_]+\s*\(/g)].length,
  0,
);

const localeFiles = (await readdir(messagesDir))
  .filter((name) => name.endsWith(".json"))
  .sort();
const catalogs = new Map();
for (const name of localeFiles) {
  catalogs.set(
    name.slice(0, -5),
    JSON.parse(await readFile(join(messagesDir, name), "utf8")),
  );
}

const base = catalogs.get("en");
if (!base) throw new Error("messages/en.json is required as the base catalog");
const baseKeys = messageKeys(base);
const catalogErrors = [];
const rows = [];
const languageNames = { en: "English", tr: "Türkçe", pt: "Português" };

for (const [locale, catalog] of catalogs) {
  const translated = baseKeys.filter(
    (key) => typeof catalog[key] === "string" && catalog[key].trim() !== "",
  );
  const missing = baseKeys.filter((key) => !translated.includes(key));
  const extra = messageKeys(catalog).filter((key) => !(key in base));
  if (missing.length > 0) {
    catalogErrors.push(`${locale}: missing ${missing.join(", ")}`);
  }
  if (extra.length > 0) {
    catalogErrors.push(`${locale}: extra ${extra.join(", ")}`);
  }
  for (const key of translated) {
    const expected = placeholders(base[key]);
    const actual = placeholders(catalog[key]);
    if (expected.join(",") !== actual.join(",")) {
      catalogErrors.push(
        `${locale}.${key}: placeholders ${actual.join(", ") || "(none)"}; expected ${expected.join(", ") || "(none)"}`,
      );
    }
  }
  rows.push({
    locale,
    label: languageNames[locale] ?? locale,
    translated: translated.length,
    total: baseKeys.length,
    percent: percent(translated.length, baseKeys.length),
  });
}

const block = coverageBlock(rows, localizedCallsites, findings);
if (writeReadme) await updateReadme(block);

const readme = await readFile(readmePath, "utf8");
const readmeCurrent = readme.includes(block);

console.log("Localization coverage");
for (const row of rows) {
  console.log(
    `  ${row.label} (${row.locale}): ${row.percent}% (${row.translated}/${row.total})`,
  );
}
console.log(
  `  UI source: ${percent(localizedCallsites, localizedCallsites + findings.length)}% (${localizedCallsites} localized call sites, ${findings.length} unlocalized literals across ${svelteFiles.length} components)`,
);

if (findings.length > 0) {
  console.error("\nPotential unlocalized UI text:");
  for (const item of findings) {
    console.error(
      `  ${item.file}:${item.line} [${item.kind}] ${JSON.stringify(item.value)}`,
    );
  }
}
if (catalogErrors.length > 0) {
  console.error("\nCatalog errors:");
  for (const error of catalogErrors) console.error(`  ${error}`);
}
if (!readmeCurrent) {
  console.error(
    "\nREADME localization coverage is stale; run npm run i18n:coverage.",
  );
}

if (findings.length > 0 || catalogErrors.length > 0 || !readmeCurrent) {
  process.exitCode = 1;
}

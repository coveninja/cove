#!/usr/bin/env node

import { readFile, readdir, stat } from 'node:fs/promises';
import { dirname, isAbsolute, normalize, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const manifestFile = resolve(root, 'docs/site/manifest.json');
const guidesDirectory = resolve(root, 'docs/site/guides');
const manifest = JSON.parse(await readFile(manifestFile, 'utf8'));
const failures = [];
const supportedSections = new Set(['Start here', 'Install', 'Use Cove', 'Get help']);

function fail(message) {
	failures.push(message);
}

function insideRoot(path) {
	const fromRoot = relative(root, path);
	return fromRoot !== '..' && !fromRoot.startsWith(`..${sep}`) && !isAbsolute(fromRoot);
}

if (manifest.schema_version !== 1) fail('schema_version must be 1');
if (!Array.isArray(manifest.pages) || manifest.pages.length === 0) {
	fail('pages must be a non-empty array');
}

const slugs = new Set();
const sourcePaths = new Set();
const orders = new Set();

for (const [index, page] of (manifest.pages ?? []).entries()) {
	const label = `pages[${index}]`;
	if (!page || typeof page !== 'object') {
		fail(`${label} must be an object`);
		continue;
	}

	for (const key of ['slug', 'title', 'summary', 'section', 'source_path']) {
		if (typeof page[key] !== 'string' || page[key].trim() === '') {
			fail(`${label}.${key} must be a non-empty string`);
		}
	}
	if (!Number.isInteger(page.order) || page.order < 0) {
		fail(`${label}.order must be a non-negative integer`);
	} else {
		if (orders.has(page.order)) fail(`duplicate order: ${page.order}`);
		orders.add(page.order);
	}

	if (!supportedSections.has(page.section)) {
		fail(`${label}.section is not supported by the website: ${page.section}`);
	}
	if (!Array.isArray(page.keywords) || page.keywords.length === 0) {
		fail(`${label}.keywords must be a non-empty array`);
	} else {
		for (const [keywordIndex, keyword] of page.keywords.entries()) {
			if (typeof keyword !== 'string' || keyword.trim() === '') {
				fail(`${label}.keywords[${keywordIndex}] must be a non-empty string`);
			}
		}
	}

	if (!/^[a-z0-9]+(?:[/-][a-z0-9]+)*$/.test(page.slug ?? '')) {
		fail(`${label}.slug is not a safe route slug`);
	}
	if (slugs.has(page.slug)) fail(`duplicate slug: ${page.slug}`);
	slugs.add(page.slug);

	if (!/^docs\/site\/guides\/[a-z0-9/-]+\.md$/.test(page.source_path ?? '')) {
		fail(`${label}.source_path must be a Markdown file under docs/site/guides`);
	}
	if (sourcePaths.has(page.source_path)) fail(`duplicate source_path: ${page.source_path}`);
	sourcePaths.add(page.source_path);

	const source = resolve(root, normalize(page.source_path ?? ''));
	if (!insideRoot(source)) {
		fail(`${label}.source_path escapes the repository`);
		continue;
	}

	let markdown;
	try {
		const info = await stat(source);
		if (!info.isFile()) throw new Error('not a file');
		if (info.size > 300_000) fail(`${page.source_path} exceeds the 300 KB document limit`);
		markdown = await readFile(source, 'utf8');
	} catch (error) {
		fail(`${page.source_path} cannot be read: ${error.message}`);
		continue;
	}

	const firstHeading = markdown.match(/^#\s+(.+)$/m)?.[1]?.trim();
	if (firstHeading !== page.title) {
		fail(`${page.source_path} must start with the H1 "${page.title}"`);
	}

	for (const match of markdown.matchAll(/!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
		const href = match[1];
		if (/^(?:https?:|mailto:|#)/.test(href)) continue;
		const targetWithoutHash = href.split('#', 1)[0];
		if (!targetWithoutHash) continue;
		const target = resolve(dirname(source), normalize(targetWithoutHash));
		if (!insideRoot(target)) {
			fail(`${page.source_path} contains a link outside the repository: ${href}`);
			continue;
		}
		try {
			await stat(target);
		} catch {
			fail(`${page.source_path} contains a broken relative link: ${href}`);
		}
	}
}

async function collectMarkdown(directory) {
	const collected = [];
	for (const entry of await readdir(directory, { withFileTypes: true })) {
		const path = resolve(directory, entry.name);
		if (entry.isDirectory()) collected.push(...await collectMarkdown(path));
		else if (entry.isFile() && entry.name.endsWith('.md')) {
			collected.push(relative(root, path).split(sep).join('/'));
		}
	}
	return collected;
}

for (const sourcePath of await collectMarkdown(guidesDirectory)) {
	if (!sourcePaths.has(sourcePath)) fail(`guide is missing from the manifest: ${sourcePath}`);
}

if (failures.length > 0) {
	for (const failure of failures) console.error(`site docs: ${failure}`);
	process.exitCode = 1;
} else {
	console.log(`Validated ${manifest.pages.length} Cove site documentation pages.`);
}

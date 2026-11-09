#!/usr/bin/env node
// Publishes or updates in Wiki.js the pages versioned in wiki/content/**/*.txt through
// the official GraphQL API (Wiki.js internals are never touched), so the whole wiki can
// be rebuilt from git at any time, e.g. after a cluster reset that wiped the Postgres volume.
'use strict';

const fs = require('fs');
const path = require('path');

const CONTENT_DIR = path.join(__dirname, '..', 'content');
const WIKI_BASE_URL = process.env.WIKI_BASE_URL || 'http://wiki.local';
const API_KEY = process.env.WIKIJS_API_KEY;
const LOCALE = 'es';

if (!API_KEY) {
  console.error('[x] WIKIJS_API_KEY is missing (define it in infra/.env.local).');
  process.exit(1);
}

// GraphQL errors (HTTP 200 with an "errors" array) do not throw: Wiki.js also uses them for
// expected cases, e.g. singleByPath returns "This page does not exist." instead of null. Each
// caller decides whether an error is expected (findPageId) or must propagate (ensureNoErrors).
async function graphql(query, variables) {
  const res = await fetch(`${WIKI_BASE_URL}/graphql`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({ query, variables }),
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} calling ${WIKI_BASE_URL}/graphql`);
  }
  const json = await res.json();
  return { data: json.data, errors: json.errors || [] };
}

function ensureNoErrors(result, context) {
  if (result.errors.length) {
    throw new Error(`${context}: ${result.errors.map((e) => e.message).join('; ')}`);
  }
  return result.data;
}

function listContentFiles(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listContentFiles(full));
    else if (entry.name.endsWith('.txt')) out.push(full);
  }
  return out;
}

async function findPageId(pagePath) {
  const result = await graphql(
    `query ($path: String!, $locale: String!) {
      pages { singleByPath(path: $path, locale: $locale) { id } }
    }`,
    { path: pagePath, locale: LOCALE },
  );
  if (result.errors.some((e) => /does not exist/i.test(e.message))) {
    return null;
  }
  const data = ensureNoErrors(result, `singleByPath('${pagePath}')`);
  return data.pages.singleByPath ? data.pages.singleByPath.id : null;
}

async function createPage({ pagePath, title, content }) {
  const result = await graphql(
    `mutation ($content: String!, $description: String!, $editor: String!, $isPublished: Boolean!, $isPrivate: Boolean!, $locale: String!, $path: String!, $tags: [String]!, $title: String!) {
      pages {
        create(content: $content, description: $description, editor: $editor, isPublished: $isPublished, isPrivate: $isPrivate, locale: $locale, path: $path, tags: $tags, title: $title) {
          responseResult { succeeded errorCode message }
          page { id path }
        }
      }
    }`,
    {
      content,
      description: '',
      editor: 'markdown',
      isPublished: true,
      isPrivate: false,
      locale: LOCALE,
      path: pagePath,
      tags: [],
      title,
    },
  );
  const data = ensureNoErrors(result, `create('${pagePath}')`);
  const outcome = data.pages.create;
  if (!outcome.responseResult.succeeded) {
    throw new Error(`create('${pagePath}') failed: ${outcome.responseResult.message}`);
  }
  return outcome.page.id;
}

async function updatePage({ id, title, content }) {
  // tags is optional in the schema, but the Wiki.js resolver crashes server-side
  // ("Cannot read properties of undefined (reading 'map')") without it, so it is always
  // sent as [] (createPage does the same).
  const result = await graphql(
    `mutation ($id: Int!, $content: String, $title: String, $isPublished: Boolean, $tags: [String]) {
      pages {
        update(id: $id, content: $content, title: $title, isPublished: $isPublished, tags: $tags) {
          responseResult { succeeded errorCode message }
          page { id path }
        }
      }
    }`,
    { id, content, title, isPublished: true, tags: [] },
  );
  const data = ensureNoErrors(result, `update(id=${id})`);
  const outcome = data.pages.update;
  if (!outcome.responseResult.succeeded) {
    throw new Error(`update(id=${id}) failed: ${outcome.responseResult.message}`);
  }
  return outcome.page.id;
}

async function main() {
  const files = listContentFiles(CONTENT_DIR);
  if (files.length === 0) {
    console.error(`[x] No .txt files in ${CONTENT_DIR}`);
    process.exit(1);
  }

  for (const file of files) {
    const relPath = path.relative(CONTENT_DIR, file).replace(/\\/g, '/');
    const pagePath = relPath.replace(/\.txt$/, '');
    const raw = fs.readFileSync(file, 'utf8');
    const title = raw.split('\n', 1)[0].replace(/^#\s*/, '').trim();

    process.stdout.write(`==> Page '${pagePath}'... `);
    const existingId = await findPageId(pagePath);
    if (existingId) {
      await updatePage({ id: existingId, title, content: raw });
      console.log(`updated (id=${existingId})`);
    } else {
      const newId = await createPage({ pagePath, title, content: raw });
      console.log(`created (id=${newId})`);
    }
  }

  console.log(`\nDone. Check ${WIKI_BASE_URL}`);
}

main().catch((err) => {
  console.error(`[x] ${err.message}`);
  process.exit(1);
});

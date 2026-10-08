#!/usr/bin/env node
// Scryfall Tagger tags: the tree, the aliases, and the cards with none.
//
// Tags come from Scryfall's oracle-tags bulk file and nowhere else. There
// is no per-card endpoint, so the Worker cannot tag a card nobody has
// owned before, and this runs nightly to catch up. It does three things:
//
//   1. Rewrites `tag_names`, which is what makes `otag:` behave the way
//      Scryfall's does. `otag:blink` finds nothing by tag name, because
//      `blink` is an alias of `flicker`; and `flicker` itself tags one
//      card, because the rest carry its children `flicker-creature`,
//      `flicker-slow` and so on. A row (name, tag) says "searching for
//      name finds cards tagged tag", for the tag, each alias, and every
//      tag beneath it in the tree.
//   2. Adds any tag the `tags` table has not seen, with its description.
//   3. Tags every card that has no tags at all, and refreshes the
//      full-text index for exactly those cards.
//
// Reads the newest `oracle-tags-*.jsonl.gz` that prefetch_scryfall.py
// keeps in ~/Library/Caches/mtg-scryfall, or --file. Writes through
// /admin/sql, signed with MTG_API_TOKEN, or with a token unlocked from
// MTG_ADMIN_PASSWORD.
//
//     node scripts/tags.mjs [--dry-run] [--api URL] [--file PATH]

import { createReadStream, readdirSync } from 'node:fs'
import { createGunzip } from 'node:zlib'
import { createInterface } from 'node:readline'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

// Rows per INSERT. D1 rejects an oversized statement, and these are
// literals rather than bound values because D1 binds at most 100.
const BATCH = 400

/** Every (search word, tag it finds) pair the tree implies. */
export function tagNames(tags) {
  const byId = new Map(tags.map((t) => [t.id, t]))
  const below = (t) => {
    const seen = new Set([t.id])
    const queue = [t]
    while (queue.length) {
      for (const c of queue.shift().child_ids || []) {
        const child = byId.get(c)
        if (child && !seen.has(c)) {
          seen.add(c)
          queue.push(child)
        }
      }
    }
    return [...seen].map((id) => byId.get(id).slug)
  }
  const rows = new Set()
  for (const t of tags) {
    const found = below(t)
    for (const name of [t.slug, ...(t.aliases || [])]) {
      for (const tag of found) rows.add(`${name.toLowerCase()}\u0000${tag}`)
    }
  }
  return [...rows].map((r) => r.split('\u0000'))
}

const quote = (v) => (v === null || v === undefined ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`)

async function readBulk(file) {
  const tags = []
  const lines = createInterface({ input: createReadStream(file).pipe(createGunzip()) })
  for await (const line of lines) if (line.trim()) tags.push(JSON.parse(line))
  return tags.filter((t) => (t.type || 'oracle') === 'oracle')
}

function newestBulk() {
  const dir = join(homedir(), 'Library/Caches/mtg-scryfall')
  const found = readdirSync(dir).filter((f) => /^oracle-tags-.*\.jsonl\.gz$/.test(f)).sort()
  if (!found.length) throw new Error(`no oracle-tags bulk file in ${dir} - run prefetch_scryfall.py first`)
  return join(dir, found.at(-1))
}

async function unlock(api, fetchImpl) {
  if (process.env.MTG_API_TOKEN) return process.env.MTG_API_TOKEN
  const password = process.env.MTG_ADMIN_PASSWORD
  if (!password) throw new Error('set MTG_API_TOKEN or MTG_ADMIN_PASSWORD')
  const r = await fetchImpl(`${api}/admin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'mtg-api-scripts/1.0' },
    body: JSON.stringify({ password }),
  })
  if (!r.ok) throw new Error(`admin unlock failed (${r.status})`)
  return (await r.json()).token
}

export async function run({ api, token, file, fetchImpl = fetch, log = console.log, dryRun = false }) {
  const sql = async (statement) => {
    const r = await fetchImpl(`${api}/admin/sql`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // Cloudflare's bot protection turns away a client that does not
        // say who it is.
        'User-Agent': 'mtg-api-scripts/1.0',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ sql: statement, params: [] }),
    })
    const text = await r.text()
    if (!r.ok) throw new Error(`API error ${r.status}: ${text.slice(0, 400)}`)
    return JSON.parse(text)
  }
  const write = (statement) => (dryRun ? null : sql(statement))
  const inBatches = async (values, head) => {
    for (let i = 0; i < values.length; i += BATCH) {
      await write(head + values.slice(i, i + BATCH).join(','))
    }
  }

  const tags = await readBulk(file)

  // 1. The tree. Cleared and refilled rather than diffed: the search
  //    still matches a tag by its own name while this runs, so the gap
  //    costs aliases and parents for a few seconds and nothing else.
  const names = tagNames(tags)
  log(`tag_names: ${names.length} rows from ${tags.length} tags`)
  await write('DELETE FROM tag_names')
  await inBatches(names.map(([n, t]) => `(${quote(n)},${quote(t)})`), 'INSERT INTO tag_names (name, tag) VALUES ')

  // 2 and 3. Cards with no tags at all, by oracle card.
  const bySlug = new Map(tags.map((t) => [t.slug, t]))
  const byOracle = new Map()
  for (const t of tags) {
    for (const { oracle_id: o } of t.taggings || []) {
      if (!byOracle.has(o)) byOracle.set(o, [])
      byOracle.get(o).push(t.slug)
    }
  }
  const untagged = (await sql(`
    SELECT c.id, c.oracle_id FROM cards c
     WHERE c.oracle_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM card_tags t WHERE t.card_id = c.id)`)).rows || []
  const values = []
  const tagged = new Set()
  const used = new Set()
  for (const [id, oracle] of untagged) {
    for (const slug of byOracle.get(oracle) || []) {
      values.push(`(${Number(id)},${quote(slug)},'oracle')`)
      tagged.add(Number(id))
      used.add(slug)
    }
  }
  log(`card_tags: ${untagged.length} cards with no tags, ${tagged.size} found in the bulk file, ${values.length} rows`)
  if (!values.length) return

  const known = new Set(((await sql('SELECT tag FROM tags')).rows || []).map((r) => r[0]))
  const fresh = [...used].filter((s) => !known.has(s)).map((s) => bySlug.get(s))
  await inBatches(
    fresh.map((t) => `(${quote(t.slug)},'oracle',${quote(t.label || t.slug)},${quote(t.description)})`),
    'INSERT INTO tags (tag, kind, label, description) VALUES ',
  )
  await inBatches(values, 'INSERT OR IGNORE INTO card_tags (card_id, tag, kind) VALUES ')

  // The full-text row for these cards was written before they had tags.
  const ids = [...tagged].sort((a, b) => a - b)
  for (let i = 0; i < ids.length; i += BATCH) {
    const list = ids.slice(i, i + BATCH).join(',')
    await write(`DELETE FROM card_search WHERE rowid IN (${list})`)
    await write(`
      INSERT INTO card_search (rowid, name, type_line, oracle_text, flavor_text, keywords, tags)
      SELECT c.id, c.name, c.type_line, c.oracle_text, c.flavor_text,
             (SELECT GROUP_CONCAT(keyword, ' ') FROM card_keywords k WHERE k.card_id = c.id),
             (SELECT GROUP_CONCAT(tag, ' ') FROM card_tags t WHERE t.card_id = c.id)
        FROM cards c WHERE c.id IN (${list})`)
  }
  log(`search: reindexed ${ids.length} cards${dryRun ? ' (dry run, nothing written)' : ''}`)
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  const args = process.argv.slice(2)
  const opt = (name) => {
    const i = args.indexOf(name)
    return i >= 0 ? args[i + 1] : undefined
  }
  const api = opt('--api') || process.env.MTG_API || 'https://mtg-api.mattshoe81.workers.dev'
  try {
    await run({
      api,
      token: await unlock(api, fetch),
      file: opt('--file') || newestBulk(),
      dryRun: args.includes('--dry-run'),
    })
  } catch (e) {
    console.error(`tags: ${e.message}`)
    process.exit(1)
  }
}

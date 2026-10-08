import { describe, it, expect } from 'vitest'
import { mkdtempSync, writeFileSync, readFileSync, existsSync, chmodSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'
import { spawnSync } from 'node:child_process'
import { tagNames, run } from '../scripts/tags.mjs'

// Shaped like Scryfall's oracle-tags bulk file, which is the only place
// the tag tree and the aliases exist. `blink` is not a tag at all: it is
// an alias of `flicker`, and `flicker` itself tags one card while its
// children tag the rest. `removal` tags nothing directly.
const T = (id, slug, { aliases = [], children = [], parents = [], cards = [] } = {}) => ({
  object: 'tag', id, slug, label: slug, type: 'oracle',
  description: `about ${slug}`, aliases, child_ids: children, parent_ids: parents,
  taggings: cards.map((oracle_id) => ({ oracle_id, weight: 'median' })),
})
const BULK = [
  T('f', 'flicker', { aliases: ['blink'], children: ['fc', 'fs'], cards: ['o-ephemerate'] }),
  T('fc', 'flicker-creature', { parents: ['f'], cards: ['o-restoration'] }),
  T('fs', 'flicker-slow', { parents: ['f'], children: ['fsx'], cards: ['o-ghostly'] }),
  T('fsx', 'flicker-slow-extra', { parents: ['fs'], cards: ['o-deep'] }),
  T('r', 'removal', { children: ['rd'] }),
  T('rd', 'removal-destroy', { parents: ['r'], aliases: ['kill'], cards: ['o-murder'] }),
]

const pairs = (rows) => rows.map(([name, tag]) => `${name} -> ${tag}`).sort()

describe('tagNames — what each search word finds', () => {
  it('an alias finds what its tag finds', () => {
    const p = pairs(tagNames(BULK))
    expect(p, 'otag:blink would still find nothing').toContain('blink -> flicker')
    expect(p).toContain('kill -> removal-destroy')
  })

  it('a tag finds every tag beneath it, however deep', () => {
    const p = pairs(tagNames(BULK))
    for (const t of ['flicker', 'flicker-creature', 'flicker-slow', 'flicker-slow-extra']) {
      expect(p, `otag:flicker misses cards tagged ${t}`).toContain(`flicker -> ${t}`)
      expect(p, `otag:blink misses cards tagged ${t}`).toContain(`blink -> ${t}`)
    }
    expect(p, 'a tag with no taggings of its own finds nothing').toContain('removal -> removal-destroy')
  })

  it('a child does not find its parent', () => {
    expect(pairs(tagNames(BULK))).not.toContain('flicker-creature -> flicker')
  })

  it('survives a loop in the tree', () => {
    const loop = [T('a', 'a', { children: ['b'] }), T('b', 'b', { children: ['a'] })]
    expect(pairs(tagNames(loop))).toEqual(['a -> a', 'a -> b', 'b -> a', 'b -> b'])
  })
})

/** A /admin/sql that runs nothing and records everything it was sent. */
function fakeApi(untagged = []) {
  const sent = []
  const fetchImpl = async (url, init) => {
    const body = JSON.parse(init.body)
    sent.push({ url, auth: init.headers.Authorization, sql: body.sql })
    const rows = /FROM cards c\s+WHERE/i.test(body.sql) ? untagged
      : /SELECT tag FROM tags/i.test(body.sql) ? [['removal-destroy']] : []
    return new Response(JSON.stringify({ cols: [], rows, n: rows.length }), { status: 200 })
  }
  return { sent, fetchImpl }
}

function bulkFile() {
  const dir = mkdtempSync(join(tmpdir(), 'tags-'))
  const file = join(dir, 'oracle-tags-20261008.jsonl.gz')
  writeFileSync(file, gzipSync(BULK.map((t) => JSON.stringify(t)).join('\n')))
  return file
}

describe('run — writing it to the database', () => {
  it('replaces tag_names with the whole tree, signed with the service token', async () => {
    const { sent, fetchImpl } = fakeApi()
    await run({ api: 'https://api.test', token: 'tok', file: bulkFile(), fetchImpl, log: () => {} })

    expect(sent.every((s) => s.url === 'https://api.test/admin/sql' && s.auth === 'Bearer tok')).toBe(true)
    const wipe = sent.findIndex((s) => /^DELETE FROM tag_names/.test(s.sql))
    const fill = sent.findIndex((s) => /^INSERT INTO tag_names/.test(s.sql))
    expect(wipe, 'tag_names was never cleared').toBeGreaterThanOrEqual(0)
    expect(fill, 'tag_names was filled before it was cleared').toBeGreaterThan(wipe)
    const inserted = sent.filter((s) => /^INSERT INTO tag_names/.test(s.sql)).map((s) => s.sql).join()
    expect(inserted).toContain("('blink','flicker-slow-extra')")
  })

  it('tags the cards that have none, and only from the bulk file', async () => {
    const { sent, fetchImpl } = fakeApi([[41, 'o-murder'], [42, 'o-nothing-known']])
    await run({ api: 'https://api.test', token: 'tok', file: bulkFile(), fetchImpl, log: () => {} })

    const tagged = sent.filter((s) => /^INSERT OR IGNORE INTO card_tags/.test(s.sql)).map((s) => s.sql).join()
    expect(tagged, 'the untagged card was not tagged').toContain("(41,'removal-destroy','oracle')")
    expect(tagged).not.toContain('(42,')
    const indexed = sent.filter((s) => /INSERT INTO card_search/.test(s.sql)).map((s) => s.sql).join()
    expect(indexed, 'the newly tagged card was not reindexed').toMatch(/IN \(41\)/)
  })

  it('adds a tag the tags table has not seen, with its description', async () => {
    const { sent, fetchImpl } = fakeApi([[41, 'o-murder'], [43, 'o-ephemerate']])
    await run({ api: 'https://api.test', token: 'tok', file: bulkFile(), fetchImpl, log: () => {} })
    const added = sent.filter((s) => /^INSERT INTO tags/.test(s.sql)).map((s) => s.sql).join()
    expect(added).toContain("'flicker'")
    expect(added).toContain("'about flicker'")
    expect(added, 'a tag already known was added twice').not.toContain("'removal-destroy'")
  })

  it('a dry run writes nothing', async () => {
    const { sent, fetchImpl } = fakeApi([[41, 'o-murder']])
    await run({ api: 'https://api.test', token: 'tok', file: bulkFile(), fetchImpl, log: () => {}, dryRun: true })
    expect(sent.filter((s) => !/^\s*SELECT/i.test(s.sql)).map((s) => s.sql)).toEqual([])
  })
})

describe('nightly.sh — tags do not wait on the backup', () => {
  it('runs the tag script even when the backup fails', () => {
    // From 30 September every backup failed verification and the script
    // exited before reaching the tag step, so nothing added after that
    // was ever tagged.
    const home = mkdtempSync(join(tmpdir(), 'nightly-'))
    const bin = join(home, 'bin')
    mkdirSync(bin)
    const marker = join(home, 'tags-ran')
    writeFileSync(join(bin, 'python3'), '#!/bin/bash\nexit 1\n')
    writeFileSync(join(bin, 'node'), `#!/bin/bash\necho "$@" >> "${marker}"\n`)
    chmodSync(join(bin, 'python3'), 0o755)
    chmodSync(join(bin, 'node'), 0o755)

    spawnSync('bash', ['scripts/nightly.sh'], {
      env: { PATH: `${bin}:/usr/bin:/bin`, HOME: home, MTG_BACKUP_DIR: join(home, 'b') },
      encoding: 'utf8',
    })
    expect(existsSync(marker), 'the tag script never ran after a failed backup').toBe(true)
    expect(readFileSync(marker, 'utf8')).toContain('scripts/tags.mjs')
  })
})

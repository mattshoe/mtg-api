// An action is an icon, not the word for it.
//
// Matt: "The agents keep doing stupid shit like using text instead of
// icons. Instead of an X icon or trash icon, just using the text
// 'remove', same with share and edit etc etc. This looks terrible and
// dated."
//
// He is right and it is not a matter of taste that can be argued with:
// a row of cards with [Remove] [Edit] [Rename] spelled out in words
// reads like a 2004 admin panel. Every app a person uses puts a bin, a
// pencil and a share arrow there.
//
// Both platforms already have somewhere for these to live and neither
// has the shapes yet:
//
//   Android  apps/androidApp/.../NavIcons.kt — Canvas paths, with
//            `at`, `line`, `box`, `circle` and `path` helpers
//   Web      apps/webApp/.../Icons.kt — a span masked to a shape by
//            `currentColor`, the shape itself a CSS rule
//
// This test is a ratchet, not a cleanup. The seventeen labels in
// `test/fixtures/text-label-actions.json` are what the codebase had
// when the rule was written; they are listed so the rule can start
// working today instead of after a cross-platform retrofit. A NEW one
// fails immediately. A fixed one has to come out of the file in the
// same commit, so the list can only ever shrink — the same bargain as
// `test/suite-floors.json`.
//
// The accessibility label is a different thing and stays a word:
// `contentDescription = "Remove"` and `aria-label="Remove"` are how a
// screen reader says what the bin does, and an icon without one is
// worse than the word.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))
const BASELINE = join(REPO, 'test', 'fixtures', 'text-label-actions.json')

/** The actions with an icon everybody already recognises. */
const WORDS = 'delete|remove|trash|share|edit|rename|copy|clear|discard'

/** Android: the label is the first argument of a clickable component. */
const ANDROID = new RegExp(String.raw`\b(?:Btn|Ghost|Chip|Pill|TextBtn)\(\s*"((?:${WORDS})\b[^"]{0,20})"`, 'i')

/** Web: a `Text("…")` inside something clickable. */
// The `\b` matters: without it "Renamed" — a status tag reading
// "Renamed" next to a tick — matched as the word "rename", and no
// amount of looking at what encloses it would have helped. An action
// label is the word, or the word and more words, never the word with
// letters stuck on the end.
const WEB = new RegExp(String.raw`\bText\(\s*"((?:${WORDS})\b[^"]{0,20})"`, 'i')

function kotlin(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e)
    if (statSync(p).isDirectory()) kotlin(p, out)
    else if (e.endsWith('.kt')) out.push(p)
  }
  return out
}

/** Every visible word-label for one of those actions, as file:label. */
function found() {
  const hits = []
  for (const p of kotlin(join(REPO, 'apps/androidApp/src/main'))) {
    readFileSync(p, 'utf8').split('\n').forEach((line, i) => {
      const m = line.match(ANDROID)
      if (m) hits.push({ key: `${basename(p)}:${m[1]}`, where: `${basename(p)}:${i + 1}`, label: m[1] })
    })
  }
  for (const p of kotlin(join(REPO, 'apps/webApp/src/jsMain'))) {
    const lines = readFileSync(p, 'utf8').split('\n')
    lines.forEach((line, i) => {
      const m = line.match(WEB)
      // Only inside a `Button(`, and twelve lines of lookback rather
      // than three.
      //
      // Three lines missed a real one: `FetchListPage.kt` builds the
      // button's `onClick` over several lines and puts `}) {
      // Text("Remove") }` well below it, so the Android half of that
      // pair was caught and the web half was not. A detector that sees
      // one platform and not the other is worse than none, because it
      // reads as parity.
      //
      // `Button(` rather than `onClick`, because widening the lookback
      // to catch that one started matching `Text("Renamed")` — a status
      // tag in a `Div`, sitting below the close button's `onClick`. A
      // label the person cannot press is not this rule's business.
      if (m && /Button\(/.test(lines.slice(Math.max(0, i - 12), i + 1).join('\n'))) {
        hits.push({ key: `${basename(p)}:${m[1]}`, where: `${basename(p)}:${i + 1}`, label: m[1] })
      }
    })
  }
  return hits
}

describe('an action is an icon, not the word for it', () => {
  const known = new Set(JSON.parse(readFileSync(BASELINE, 'utf8')))

  it('has no word-labelled action that is not already on the list', () => {
    const fresh = found().filter((h) => !known.has(h.key))
    expect(
      fresh.map((h) => `${h.where} "${h.label}"`),
      'A new action is spelled out in words. Draw it instead: NavIcons.kt on\n'
      + 'Android, Icons.kt plus a CSS mask on the web. Keep the word as the\n'
      + 'contentDescription or aria-label so a screen reader still says it.\n'
      + 'If it genuinely has no icon anybody would recognise, add it to\n'
      + 'test/fixtures/text-label-actions.json and say why in the commit',
    ).toEqual([])
  })

  it('has no entry on the list that has already been fixed', () => {
    // The ratchet. A label that is gone comes off the list in the same
    // commit, or the list stops meaning anything.
    const keys = new Set(found().map((h) => h.key))
    const stale = [...known].filter((k) => !keys.has(k))
    expect(
      stale,
      'These are fixed. Take them out of test/fixtures/text-label-actions.json\n'
      + 'in the same commit, so the list can only ever shrink',
    ).toEqual([])
  })

  it('counts the ones still to do, so the number is visible', () => {
    // Not an assertion about the number, a statement of it. 18 when the
    // rule was written, and it is only allowed to go down.
    expect(known.size).toBeLessThanOrEqual(18)
  })
})

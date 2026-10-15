import { describe, it, expect } from 'vitest'
import listJson from './fixtures/text-label-actions.json?raw'

// Imported at build time: this runs inside workerd, which has no
// filesystem to read them from.
const ANDROID = import.meta.glob('../apps/androidApp/src/main/kotlin/**/*.kt', { query: '?raw', import: 'default', eager: true })
const WEB = import.meta.glob('../apps/webApp/src/jsMain/kotlin/**/*.kt', { query: '?raw', import: 'default', eager: true })

/**
 * Matt: "The agents keep doing stupid shit like using text instead of
 * icons. Instead of an X icon or trash icon, just using the text
 * 'remove', same with share and edit etc etc."
 *
 * These are drawn, not written. The accessibility label stays a word,
 * so this looks only at what is painted: the first argument of a
 * button, or the `Text` a button holds.
 */
const WORDS = ['delete', 'remove', 'trash', 'share', 'edit', 'edit list', 'rename', 'rename deck', 'copy', 'clear', 'discard']

const word = `"(${WORDS.join('|')})"`
// `Btn("Remove", …)`, `Ghost("Clear")`, `Text("Edit")`, and the busy
// form `Text(if (busy) "Renaming…" else "Rename")`.
const PAINTED = new RegExp(
  `\\b(?:Btn|Ghost|Primary|Text)\\(\\s*(?:if \\([^)]*\\) "[^"]*" else )?${word}\\s*[,)]`,
  'gi',
)
// A heading names what the page or dialog is. It is not something to press.
const HEADING = /(?:title\s*=\s*\{|\bH[1-4]\s*\{)\s*Text\($/

/** The action words painted on one line of source. */
function painted(line) {
  const code = line.trim()
  if (code.startsWith('//') || code.startsWith('*')) return []
  return [...line.matchAll(PAINTED)]
    .filter((m) => !HEADING.test(line.slice(0, m.index + 5)))
    .map((m) => m[1])
}

/** Every word-labelled action, as `path:Label`, one entry per place. */
function found() {
  return Object.entries({ ...ANDROID, ...WEB })
    .flatMap(([path, text]) => text.split('\n').flatMap((line) =>
      painted(line).map((label) => `${path.replace(/^\.\.\//, '')}:${label}`)))
    .sort()
}

const list = JSON.parse(listJson)
const tally = (xs) => xs.reduce((m, x) => ({ ...m, [x]: (m[x] ?? 0) + 1 }), {})

describe('an action is an icon, not the word for it', () => {
  it('reads the screens it is meant to police', () => {
    expect(Object.keys(ANDROID).length, 'no Android screens were read').toBeGreaterThan(10)
    expect(Object.keys(WEB).length, 'no web pages were read').toBeGreaterThan(10)
  })

  it('knows a button from a heading and from a label', () => {
    expect(painted('Ghost("Remove") { onRemove() }')).toEqual(['Remove'])
    expect(painted('Btn("Remove", danger = true) { onTweak(row, how) }')).toEqual(['Remove'])
    expect(painted('}) { Text(if (state.busy) "Renaming…" else "Rename") }')).toEqual(['Rename'])
    expect(painted('H2 { Text("Rename deck") }'), 'a heading counted as an action').toEqual([])
    expect(painted('title = { Text("Edit list") },'), 'a dialog title counted as an action').toEqual([])
    expect(painted('attr("aria-label", "Remove")'), 'the spoken label counted as painted').toEqual([])
    expect(painted('IconAction("Remove", Glyph.BIN) { onRemove() }'), 'an icon counted as a word').toEqual([])
  })

  it('has no word-labelled action that is not already on the backlog', () => {
    const allowed = tally([...list.backlog, ...Object.keys(list.confirmations)])
    const extra = Object.entries(tally(found()))
      .filter(([k, n]) => n > (allowed[k] ?? 0))
      .map(([k]) => k)
    expect(extra, 'these actions are spelled out: draw them (NavIcons.kt, Icons.kt) and keep the word as the label').toEqual([])
  })

  it('has nothing on the backlog that is already fixed, so the list only shrinks', () => {
    const have = tally(found())
    const stale = Object.entries(tally(list.backlog))
      .filter(([k, n]) => n > (have[k] ?? 0))
      .map(([k]) => k)
    expect(stale, 'these are icons now: take them off test/fixtures/text-label-actions.json').toEqual([])
  })

  it('keeps a word only under a dialog that asks the same thing, and says so', () => {
    const have = found()
    for (const [k, why] of Object.entries(list.confirmations)) {
      expect(have, `${k} is listed as a confirmation and is not there`).toContain(k)
      expect(why.length, `${k} is kept as a word with no reason given`).toBeGreaterThan(20)
    }
  })
})

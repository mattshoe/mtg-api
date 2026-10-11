import { describe, it, expect } from 'vitest'
import baselineJson from './fixtures/summary-baseline.json?raw'

// Imported at build time: this runs inside workerd, which has no
// filesystem to read them from.
const DONE = import.meta.glob('../requests/done/*.md', { query: '?raw', import: 'default', eager: true })
const SUMMARIES = import.meta.glob('../summaries/*.md', { query: '?raw', import: 'default', eager: true })

const nameOf = (path) => path.split('/').pop().replace(/\.md$/, '')
const writeups = Object.fromEntries(Object.entries(SUMMARIES).map(([p, text]) => [nameOf(p), text]))
const baseline = new Set(JSON.parse(baselineJson).names)

/**
 * What is wrong with a writeup, or nothing.
 *
 * Matt: "for bugs i want to know what was wrong or what was fixed or why
 * something was broken or whether it was unreproduceable etc. for
 * features i want a very high level summary of what was implemented,
 * liberties taken, design choices". So a writeup says which it is by its
 * first label, and then carries the lines that kind owes him.
 */
function problems(text) {
  const body = text.replace(/\r\n/g, '\n').replace(/^---\n[\s\S]*?\n---\n/, '')
  const has = (label) => new RegExp(`^${label}:\\s*\\S`, 'm').test(body)
  if (has('What was wrong')) {
    return has('Could not reproduce') ? [] : ['Why', 'Fix'].filter((l) => !has(l))
  }
  if (has('What was built')) return ['Choices', 'Liberties'].filter((l) => !has(l))
  return ['What was wrong or What was built']
}

describe('every request filed as done has a writeup for its task page', () => {
  it('a bug says what was wrong, why and the fix, a feature says what was built, its choices and its liberties', () => {
    expect(problems('What was wrong: x\nWhy: y\nFix: z')).toEqual([])
    expect(problems('What was wrong: x\nCould not reproduce: tried it on both')).toEqual([])
    expect(problems('What was wrong: x\nFix: z')).toEqual(['Why'])
    expect(problems('What was built: x\nChoices: y\nLiberties: none')).toEqual([])
    expect(problems('What was built: x\nChoices: y')).toEqual(['Liberties'])
    expect(problems('Deck colours show on the deck page.')).toEqual(['What was wrong or What was built'])
    expect(problems('What was built:\nChoices: y\nLiberties: z'), 'an empty label counted').toEqual(['What was wrong or What was built'])
  })

  it('every request under requests/done/ since summaries/ began has summaries/<name>.md, and it says the right things', () => {
    const missing = []
    for (const path of Object.keys(DONE)) {
      const name = nameOf(path)
      if (baseline.has(name)) continue
      const text = writeups[name]
      if (text == null) missing.push(`${name}: no summaries/${name}.md`)
      else {
        const lacking = problems(text)
        if (lacking.length) missing.push(`${name}: summaries/${name}.md has no ${lacking.join(', ')}`)
      }
    }
    expect(missing, 'filed as done with no useful writeup, so the task page says nothing about what happened').toEqual([])
  })

  it('the baseline only names requests that were really filed', () => {
    const done = new Set(Object.keys(DONE).map(nameOf))
    expect([...baseline].filter((n) => !done.has(n))).toEqual([])
  })
})

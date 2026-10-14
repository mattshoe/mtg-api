import { describe, it, expect } from 'vitest'
import { call } from './helpers.js'

// The Comprehensive Rules, for the Guide's rules page.
//
// Matt: "Ideally, this would use the live rule set, then parse it out
// on-device to save server processing power". Wizards publishes the rules
// as one text file at a dated address, linked from its rules page, and it
// sends no CORS header, so the website cannot fetch it itself. The Worker
// finds the current link and hands the file back byte for byte. It does
// not parse it: both apps do that, in `:core`.

const PAGE = 'https://magic.wizards.com/en/rules'
const FILE = 'https://media.wizards.com/2026/downloads/MagicCompRules 20260925.txt'
const TEXT = 'Magic: The Gathering Comprehensive Rules\n\n702.19. Trample\n\n702.19a Trample is a static ability.\n'

/** Wizards, answering `pages` by address and recording what it was asked. */
function wizards(pages) {
  const asked = []
  const impl = async (url) => {
    asked.push(String(url))
    const page = pages[String(url)]
    if (page === undefined) return new Response('not found', { status: 404 })
    if (page instanceof Error) throw page
    return new Response(page, { headers: { 'content-type': 'text/plain' } })
  }
  return { asked, impl }
}

const rulesPage = `<html><a href="https://media.wizards.com/2026/downloads/MagicCompRules%2020260925.pdf">PDF</a>
<a class="cta" href="${FILE}">TXT</a></html>`

const get = (w) => call('/rules', { method: 'GET', wizardsFetch: w.impl })

describe('GET /rules', () => {
  it('hands back the text file the rules page links to, untouched', async () => {
    const w = wizards({ [PAGE]: rulesPage, [encodeURI(FILE)]: TEXT })
    const r = await get(w)
    expect(r.status).toBe(200)
    expect(r.text).toBe(TEXT)
    expect(r.headers.get('content-type')).toMatch(/^text\/plain/)
    expect(r.headers.get('x-rules-source')).toBe(FILE)
    expect(w.asked).toEqual([PAGE, encodeURI(FILE)])
  })

  it('says so with a 502 when the rules page links no text file', async () => {
    const r = await get(wizards({ [PAGE]: '<html>moved</html>' }))
    expect(r.status).toBe(502)
    expect(r.body.error).toMatch(/no link to the rules text/)
  })

  it('says so with a 502 when Wizards answers the file with an error', async () => {
    const r = await get(wizards({ [PAGE]: rulesPage }))
    expect(r.status).toBe(502)
    expect(r.body.error).toMatch(/Wizards answered 404/)
  })

  it('says so with a 502 when Wizards does not answer at all', async () => {
    const r = await get(wizards({ [PAGE]: new Error('connection reset') }))
    expect(r.status).toBe(502)
    expect(r.body.error).toMatch(/Wizards did not answer: connection reset/)
  })

  it('is read only', async () => {
    const r = await call('/rules', { method: 'POST', body: {} })
    expect(r.status).toBe(405)
  })
})

import { describe, it, expect } from 'vitest'
import { call } from './helpers.js'

// A commander's rank, as EDHREC's own commander page states it.
//
// The rank on every card is Scryfall's `edhrec_rank`, which places a card
// among every card there is: Teysa Karlov is 1,330th. EDHREC's commander
// page places her among commanders, 58th, and that is the number Matt
// read and the app did not have. These are what the Worker hands back.

/** EDHREC, answering `pages` by slug and recording what it was asked. */
function edhrec(pages = {}) {
  const asked = []
  const impl = async (url) => {
    asked.push(String(url))
    const slug = /\/pages\/commanders\/([^/]+)\.json$/.exec(String(url))?.[1]
    const page = pages[slug]
    if (!page) {
      return new Response('<Error><Code>AccessDenied</Code></Error>', { status: 403 })
    }
    return new Response(JSON.stringify(page), { headers: { 'content-type': 'application/json' } })
  }
  return { asked, impl }
}

const teysa = {
  container: { json_dict: { cardlists: [] } },
  card: { name: 'Teysa Karlov', num_decks: 21728, rank: 58, salt: 0.53 },
}

const rank = (name, e) => call(`/edhrec/commander?name=${encodeURIComponent(name)}`, {
  method: 'GET', edhrecFetch: e.impl,
})

describe('GET /edhrec/commander', () => {
  it("says Teysa Karlov is 58th among commanders, as EDHREC's page does", async () => {
    const e = edhrec({ 'teysa-karlov': teysa })
    const r = await rank('Teysa Karlov', e)
    expect(r.status).toBe(200)
    expect(r.body).toEqual({ name: 'Teysa Karlov', slug: 'teysa-karlov', rank: 58, decks: 21728 })
    expect(e.asked).toEqual(['https://json.edhrec.com/pages/commanders/teysa-karlov.json'])
  })

  it("asks for the address EDHREC itself uses: no apostrophes, no commas, no accents, front face only", async () => {
    const e = edhrec()
    await rank("Y'shtola, Night's Blessed", e)
    await rank('Lim-Dûl the Necromancer', e)
    await rank('Esika, God of the Tree // The Prismatic Bridge', e)
    expect(e.asked).toEqual([
      'https://json.edhrec.com/pages/commanders/yshtola-nights-blessed.json',
      'https://json.edhrec.com/pages/commanders/lim-dul-the-necromancer.json',
      'https://json.edhrec.com/pages/commanders/esika-god-of-the-tree.json',
    ])
  })

  it('a card EDHREC has no commander page for is unranked, not an error', async () => {
    const r = await rank('Lightning Bolt', edhrec())
    expect(r.status).toBe(200)
    expect(r.body).toEqual({ name: 'Lightning Bolt', slug: 'lightning-bolt', rank: null, decks: null })
  })

  it('EDHREC not answering is a 502 that says so', async () => {
    const r = await rank('Teysa Karlov', { impl: async () => { throw new Error('connect ECONNREFUSED') } })
    expect(r.status).toBe(502)
    expect(r.body.error).toMatch(/EDHREC did not answer/)
  })

  it('no name is a 400', async () => {
    const r = await call('/edhrec/commander', { method: 'GET', edhrecFetch: edhrec().impl })
    expect(r.status).toBe(400)
  })
})

// Deck operations that are more than one statement.
//
// Disassembling is the only one so far. It reads as "move the cards to
// bulk", but nothing about a card moves: `cards` already records what is
// owned, and `deck_cards` is the only thing claiming any of it. Bulk is
// not a place, it is the `card_usage` view's `free` column — owned minus
// the copies some deck has spoken for. Delete the deck and its list and
// every copy it held falls free on its own.
//
// Which means the whole job is three deletes, and the only thing that
// matters is that they are one batch: a half-deleted deck leaves
// deck_cards rows pointing at a decks row that is gone, and those keep
// counting against `free` forever.

/**
 * What a deck is holding, by name, for the confirmation step.
 * Only rows backed by something owned — a gap was never held.
 */
async function heldBy(db, deckId) {
  const r = await db.prepare(`
    SELECT dc.name, SUM(dc.qty) AS qty
      FROM deck_cards dc
     WHERE dc.deck_id = ? AND dc.in_collection = 1
     GROUP BY dc.name_norm
     ORDER BY dc.name`).bind(deckId).all();
  return r.results || [];
}

/**
 * Delete a deck and free everything it was holding.
 *
 * @param {D1Database} db
 * @param {{slug?: string, dry_run?: boolean}} body
 */
export async function disassembleDeck(db, body) {
  const slug = String(body?.slug || '').trim();
  if (!slug) return { status: 400, body: { error: 'slug is required' } };

  const deck = await db.prepare(
    'SELECT id, slug, name, owner, card_count FROM decks WHERE slug = ?',
  ).bind(slug).first();
  if (!deck) return { status: 404, body: { error: `no deck with slug "${slug}"` } };

  const held = await heldBy(db, deck.id);
  const freed = held.reduce((a, r) => a + r.qty, 0);

  const counts = await db.prepare(`
    SELECT (SELECT COUNT(*) FROM deck_cards WHERE deck_id = ?1) AS cards,
           (SELECT COUNT(*) FROM deck_notes WHERE deck_id = ?1) AS notes`)
    .bind(deck.id).first();

  const out = {
    deck: { slug: deck.slug, name: deck.name, owner: deck.owner },
    freed,
    cards: held,
    rows: { deck_cards: counts?.cards ?? 0, deck_notes: counts?.notes ?? 0 },
  };

  if (body?.dry_run) return { status: 200, body: { ...out, applied: false, dry_run: true } };

  // Children first, so an interrupted batch can never orphan them.
  await db.batch([
    db.prepare('DELETE FROM deck_notes WHERE deck_id = ?').bind(deck.id),
    db.prepare('DELETE FROM deck_cards WHERE deck_id = ?').bind(deck.id),
    db.prepare('DELETE FROM decks WHERE id = ?').bind(deck.id),
  ]);

  return { status: 200, body: { ...out, applied: true, dry_run: false } };
}

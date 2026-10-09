// The release notes, through the Worker.
//
// GitHub is the record: `release.yml` cuts a release on every merge and
// puts the hand-written note in it. The app used to ask GitHub itself,
// unauthenticated from the phone at sixty asks an hour per address, and
// Matt saw the panel fail with "API rate limit exceeded". So the Worker
// asks, keeps the answer in `github_releases`, asks again at most every
// few minutes, and serves the last answer when GitHub refuses.

const URL = 'https://api.github.com/repos/mattshoe/mtg-api/releases?per_page=50';
const FRESH_MS = 10 * 60_000;

/** GitHub's release list as JSON text, `{ body }`, or `{ error }` saying why there is none. */
export async function releases(db, fetchImpl) {
  const kept = await db.prepare('SELECT body, fetched_at FROM github_releases WHERE id = 1').first();
  if (kept && Date.now() - Date.parse(kept.fetched_at) < FRESH_MS) return { body: kept.body };
  let refusal;
  try {
    const res = await fetchImpl(URL, {
      headers: { accept: 'application/vnd.github+json', 'user-agent': 'mtg-api (mattshoe/mtg-api)' },
    });
    const text = await res.text();
    if (res.ok && text.trimStart().startsWith('[')) {
      // Only what the app reads. GitHub's full answer is mostly uploader
      // and asset metadata, and a D1 row stops at 2 MB.
      const slim = JSON.stringify(JSON.parse(text).map((r) => ({
        tag_name: r.tag_name, published_at: r.published_at, body: r.body,
      })));
      await db.prepare(
        'INSERT INTO github_releases (id, body, fetched_at) VALUES (1, ?1, ?2) '
        + 'ON CONFLICT(id) DO UPDATE SET body = excluded.body, fetched_at = excluded.fetched_at',
      ).bind(slim, new Date().toISOString()).run();
      return { body: slim };
    }
    try { refusal = JSON.parse(text).message; } catch { /* not JSON */ }
    refusal ||= `GitHub answered ${res.status}`;
  } catch (e) {
    refusal = `GitHub did not answer: ${e.message}`;
  }
  return kept ? { body: kept.body } : { error: refusal };
}

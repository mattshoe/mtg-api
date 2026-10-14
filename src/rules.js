// The Comprehensive Rules, for the Guide's rules page.
//
// Matt: "Ideally, this would use the live rule set, then parse it out
// on-device to save server processing power". Wizards publishes the rules
// as one text file at an address that changes with every update, linked
// from its rules page, and it sends no CORS header, so the website cannot
// fetch it itself. The Worker finds the current link and relays the file
// byte for byte. It does not parse it: both apps do that, in `:core`
// (`Rules.kt`). Cloudflare is told to keep both answers a day; the rules
// change a few times a year.

const PAGE = 'https://magic.wizards.com/en/rules';
const LINK = /https:\/\/media\.wizards\.com\/[^"'<>]+?\.txt/i;
const KEEP = { cacheTtl: 86_400, cacheEverything: true };
const HEADERS = { 'user-agent': 'mtg-api (mattshoe/mtg-api)' };

/** `{ text, source }`, the rules file and where it came from, or `{ error }` saying why there is none. */
export async function rules(fetchImpl) {
  try {
    const page = await fetchImpl(PAGE, { headers: HEADERS, cf: KEEP });
    if (!page.ok) return { error: `Wizards answered ${page.status} for the rules page` };
    const found = LINK.exec(await page.text())?.[0];
    if (!found) return { error: 'the Wizards rules page has no link to the rules text' };
    // The page prints the address with a bare space in it.
    const source = decodeURI(found);
    const file = await fetchImpl(encodeURI(source), { headers: HEADERS, cf: KEEP });
    if (!file.ok) return { error: `Wizards answered ${file.status} for the rules text` };
    return { text: await file.text(), source };
  } catch (e) {
    return { error: `Wizards did not answer: ${e.message}` };
  }
}

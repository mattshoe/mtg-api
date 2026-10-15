import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

// Matt: "change the 'remove' text on the fetch list carousel to just an
// icon. and stop using text for buttons when an icon would suffice. like
// globally". And before that: "Instead of an X icon or trash icon, just
// using the text 'remove', same with share and edit etc etc. This looks
// terrible and dated."
//
// So a button whose whole face is one of these words is the mistake.
// The word stays as the button's label for a screen reader
// (`contentDescription`, `aria-label`), which is not what this reads.
//
// A ratchet. `fixtures/text-label-actions.json` lists what is allowed to
// stay a word, as "path: Label". A NEW one fails here; a fixed one has
// to come off the list in the same commit, so the list only shrinks.
// Confirmation dialogs are the exception the rule makes, and their
// buttons are written `Text(if (busy) "Renaming…" else "Rename")`,
// which this does not read as a bare label.

const ROOT = join(import.meta.dirname, '..');
const WORDS = ['Remove', 'Delete', 'Edit', 'Rename', 'Share', 'Copy', 'Clear', 'Discard', 'Trash'];
const WORD = `(${WORDS.join('|')})`;

// Android: the app's own buttons take their face as the first argument.
const PHONE = new RegExp(`\\b(?:Btn|Ghost|Primary)\\(\\s*"${WORD}"`, 'g');
// Web: a button's content lambda that is a single `Text("Word")`.
const WEB = new RegExp(`\\{\\s*Text\\(\\s*"${WORD}"\\s*\\)\\s*\\}`, 'g');

export function wordsOnButtons(source, platform) {
  const re = platform === 'phone' ? PHONE : WEB;
  return [...source.matchAll(re)].map((m) => m[1]);
}

function kotlin(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return kotlin(p);
    return p.endsWith('.kt') ? [p] : [];
  });
}

function found() {
  const sides = [
    ['phone', join(ROOT, 'apps/androidApp/src/main/kotlin')],
    ['web', join(ROOT, 'apps/webApp/src/jsMain/kotlin')],
  ];
  return sides.flatMap(([platform, dir]) => kotlin(dir).flatMap((file) =>
    wordsOnButtons(readFileSync(file, 'utf8'), platform)
      .map((word) => `${relative(ROOT, file)}: ${word}`))).sort();
}

const allowed = JSON.parse(readFileSync(join(ROOT, 'test/fixtures/text-label-actions.json'), 'utf8'));

describe('an action is an icon, not the word for it', () => {
  it('reads a worded button on each platform, so an empty answer means something', () => {
    expect(wordsOnButtons('Ghost("Remove") { onRemove() }', 'phone')).toEqual(['Remove']);
    expect(wordsOnButtons('Btn("Rename") { go() }', 'phone')).toEqual(['Rename']);
    expect(wordsOnButtons('}) { Text("Delete") }', 'web')).toEqual(['Delete']);
    expect(wordsOnButtons('onClick { x() } }) {\n    Text("Remove")\n  }', 'web')).toEqual(['Remove']);
    // A label for a screen reader is not a face.
    expect(wordsOnButtons('attr("aria-label", "Remove")', 'web')).toEqual([]);
    expect(wordsOnButtons('contentDescription = "Remove"', 'phone')).toEqual([]);
    // A confirmation dialog's button says its word, and is allowed to.
    expect(wordsOnButtons('{ Text(if (state.busy) "Renaming…" else "Rename") }', 'web')).toEqual([]);
  });

  it('no new button is a word where an icon would do', () => {
    const extra = found().filter((f) => !allowed.includes(f));
    expect(extra, `these buttons spell out an action instead of drawing it: ${extra.join(', ')}`).toEqual([]);
  });

  it('a button drawn since comes off the list, so the list only shrinks', () => {
    const now = found();
    const stale = allowed.filter((a) => !now.includes(a));
    expect(stale, `no longer a word, take these off test/fixtures/text-label-actions.json: ${stale.join(', ')}`).toEqual([]);
  });
});

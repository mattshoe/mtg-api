-- What an `otag:` search word finds, the way Scryfall answers it.
--
-- `card_tags` holds the tags Tagger put on each card, and those are
-- leaves: `flicker-creature`, never `flicker`. Scryfall's `otag:flicker`
-- also finds every tag beneath `flicker`, and `otag:blink` works because
-- `blink` is an alias of it. A row here says "searching for `name` finds
-- cards tagged `tag`", for each tag, each alias and every descendant.
--
-- Written whole by `scripts/tags.mjs` from the oracle-tags bulk file,
-- which is the only place the tree exists. Additive: a search still
-- matches a tag by its own name with this table empty.
CREATE TABLE IF NOT EXISTS tag_names (name TEXT NOT NULL, tag TEXT NOT NULL,
                                      UNIQUE(name, tag));
CREATE INDEX IF NOT EXISTS idx_tag_names_name ON tag_names(name);

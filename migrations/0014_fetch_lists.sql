-- Saved Fetch lists, kept against the account rather than the device.
--
-- Matt: "making a fetch list on web does not show on android and vice
-- versa". They lived in localStorage and SharedPreferences, so each
-- device had its own. One row per account now, holding every saved list
-- as the apps send it, and both apps read and write the same row.
--
-- One row and not one per list: the apps already hold the whole set and
-- write it whole, so a delete on one device is a delete everywhere with
-- nothing to reconcile list by list.
CREATE TABLE IF NOT EXISTS fetch_lists (
  user_id    INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  lists      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

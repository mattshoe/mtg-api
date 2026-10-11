# Summaries

One file per request, `summaries/<request name>.md`, written by the
builder that did it. A done task's page in Admin Settings shows it as the
Summary, on the website and on the phone, read through the Worker from
main.

The release note is one changelog line for the release. This is the
account of the work, for Matt. His words:

> for bugs i want to know what was wrong or what was fixed or why
> something was broken or whether it was unreproduceable etc
>
> for features i want a very high level summary of what was implemented,
> liberties taken, design choices

Plain text, labelled lines, a few sentences each. It is shown as typed,
so no markdown headings or bullets. Leave out test names, red runs and
file lists: those belong in the pull request.

A bug:

```
What was wrong: Swapping cards on a deck got glitchier the more you did it.
Why: Swiping the carousel or tapping outside the swap sheet restored the whole app's state from when the sheet opened, deck list included, so the next swap was planned against the old list.
Fix: Closing the sheet restores the sheet and nothing else. Phone and website.
```

A bug that would not reproduce swaps `Why` and `Fix` for one line:

```
What was wrong: The decks page took forever on a phone.
Could not reproduce: Under a second on the phone and the website, with the live collection. Nothing changed.
```

A feature:

```
What was built: A creature types chart on the deck page. Tap a bar for a carousel of those creatures.
Choices: The biggest types get bars and the rest fold into Other, so the chart fits a phone.
Liberties: A changeling counts under every type. The request did not say.
```

`Liberties: none` is a fine answer. A missing line is not.

`test/summaries.test.js` fails CI for any request under `requests/done/`
without one of these, or with one missing a line its kind owes. The 38
requests filed before this existed are listed in
`test/fixtures/summary-baseline.json` and fall back to their release
note. Nothing new goes in that list.

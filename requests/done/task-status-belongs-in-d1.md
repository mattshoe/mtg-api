---
status: ready
---

# Task status must live in D1, not be inferred from branch names

Matt: "God FUCKING dammit i put explicit fucking instructions in there to
design it so that it could be easily extended in the future!!!!!! The system
is just looking at fucking branch names?!?!"

He is right. The original request said, in his own words:

> This agents job is to evaluate the plan for simplicity-first approach that
> solves the problem at hand. Keeping in mind that we may introduce new
> features later like perhaps submitting tasks through the app or canceling
> tasks through the app etc

A view derived from GitHub branches and pull requests cannot support either
of those, which is what that sentence existed to prevent.

Where it stands today, which is split down the middle:

- Submitting works through D1 — `/tasks`, `/tasks/inbox`,
  `/tasks/inbox/received` on the Worker (src/index.js:749).
- Status is inferred from GitHub — a `request/*` branch with no pull request
  reads as `building`, an open one as `in review`, merged as `done`. Release
  notes likewise come from api.github.com
  (apps/core-net/.../GitHubReleases.kt:68).

So there are two sources of truth for one thing, and the GitHub half is
called unauthenticated from the phone: 60 requests an hour per IP. Matt saw
both panels fail at once with "API rate limit exceeded".

What is wanted: task status lives in D1, written by the dispatcher as each
transition actually happens, and the app asks the Worker and nothing else.
Cancelling a task should become a write to that row rather than something
with no home, and the inbox that already exists should be the same table
rather than a second one.

Do not break the existing Tasks panel, the New task button, or the release
notes while moving this.

## And the statuses must tell the truth, in detail

Matt: "GIVE THE FUCKING STATUSES MORE FUCKING GRANULAR INFORMATION!!! LIKE
PENDING, IN PROGRESS, BLOCKED, PAUSED, CANCELLED, ETC ETC ETC ETC!!!"

Inferring from branches cannot express anything except what a branch looks
like, so a paused task, a cancelled one, a dead one and one being actively
worked on all read `building`. Matt watched four stopped agents all still
saying `building`, which is what started this.

The status is a recorded fact, written when it happens. It must be granular
enough to answer "what is actually going on with this" without opening
anything. At least these, and add more where the system can tell them apart:

| status | what it means |
|---|---|
| pending | accepted, queued, nothing has started |
| in progress | an agent is working on it RIGHT NOW, this second |
| blocked | it needs Matt, or something it depends on — say which |
| paused | nothing is running and the work is kept — the reason says why |
| in review | pull request open — CI running, red, green, whatever |
| merged | landed on main |
| deployed | live, the artifact verified |
| cancelled | withdrawn; it says so rather than disappearing |

There is deliberately no separate `stopped`. Matt: "The fuck is the
difference between paused and stopped then???" — and he is right, the only
difference was intent, which is a reason and not a status. One `paused`,
carrying why: "held by Matt", "rate limited", "agent crashed", "killed".
Both mean the same thing to him: nothing is running, the work is safe, and
it will pick back up.

There is deliberately no `failing` state. Matt: "What the fuck does failing
mean?!?! I don't care if it's just working through a couple fucking tests on
CI!!!" A red check mid-run is the agent's problem and it fixes it; a task
with an open pull request is `in review` until it merges or somebody stops
it. Do not surface CI churn as a status.

`in progress` means a process is alive on it at this moment and nothing else.
Every other state is written by whoever caused it: the dispatcher when it
starts, stops or pauses an agent; the merge when it lands; the withdrawal
when a request is taken back.

Carry the detail with the status, not just the word — how long it has been in
that state, why it is blocked, which pull request, which CI job is red, what
the last thing that happened was. A row should answer the question without
Matt having to ask me.

## Which ones collapse into Done

Matt's original ask was "I want the done ones minimized by default but still
browsable, ordered by the time which they completed, most recent first". So
which statuses count as done has to be decided, not left to whatever the UI
happens to do:

**Done**, collapsed by default, newest finished first: `merged`, `deployed`,
`cancelled`. These are finished — nothing is going to happen to them on its
own.

**The live list**, always visible: `pending`, `in progress`, `blocked`,
`paused`, `in review`. Every one of those is either moving or waiting on
somebody, and `blocked` and `paused` in particular are the ones Matt needs to
see, because they are the ones that sit there forever if nobody looks.

A task that leaves `cancelled` or comes back from `paused` moves back up into
the live list. Done is a consequence of the status, never a separate flag
that can disagree with it.

## Write the new paradigm into the skill, in the same pull request

Every agent reads `.claude/skills/mtg/SKILL.md` before it does anything, and
`.claude/agents/request-builder.md` after. Both have to describe this as it
now is, or the next agent will reach for GitHub again — the way the last one
did, because nothing told it otherwise.

What they must say:

- Task status lives in D1 and is written when a transition happens. It is
  never inferred from a branch name, a pull request, or anything on GitHub.
  Inferring is how a paused task, a cancelled one and a dead one all came to
  read `building`.
- The app asks the Worker and only the Worker. No client calls
  api.github.com: unauthenticated it is 60 requests an hour per IP, and both
  Admin Settings panels died of exactly that on Matt's phone.
- The status vocabulary, with what each word means and which of them collapse
  into Done — the table above, not a paraphrase of it.
- Who writes each transition, so an agent adding a state later knows where it
  goes.

Treat a document that disagrees with the code as a defect in this pull
request, not a follow-up. Documentation that lies has cost this project more
than one wasted agent.

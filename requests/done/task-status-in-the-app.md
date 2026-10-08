---
status: ready
---

# See the status of ongoing tasks in the app

This one will be very involved and will require you to sit back and plan it
out, taking your good old time to get it right.

The ask:

I want to be able to see the status of ongoing tasks in the app. Nothing too
fancy just the literal status like hold or done or whatever statuses you
assign. I want the done ones minimized by default but still browsable,
ordered by the time which they completed, most recent first

Guidance:

This can get very risky, so i want you to take a great deal of care while
designing this. My guidance is as follows:

1. When you start, the first thing you need to do is come up with a detailed
   plan for how to implement this effectively and safely (without disrupting
   production).
2. Send that plan through an adversarial review from a sub agent with no
   context other than the normal context that would be given to any task for
   this project. This agents job is to evaluate the plan for simplicity-first
   approach that solves the problem at hand. Keeping in mind that we may
   introduce new features later like perhaps submitting tasks through the app
   or canceling tasks through the app etc (but not to be done initially)
3. Address any significant feedback on the plan
4. Repeat steps 1-3 until 2 consecutive reviews come back green
5. Implement
6. Another adversarial review of the implementation with exactly the same
   criteria as 2
7. Address any significant feedback from the review
8. Repeat steps 5-7 until 2 consecutive reviews come back green
9. Finalize implementation and merge code

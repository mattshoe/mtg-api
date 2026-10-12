What was wrong: mattshoe81@gmail.com no longer had the admin role.
Why: Not found. Nothing in the code takes a role away. The agent's own token is a plain user, so it could not use Admin Settings to put the role back, and the first-admin migration matched a slug that is no longer used.
Fix: A new migration gives the admin role to whichever account has the email mattshoe81@gmail.com, in any case, and touches no other account. It runs on the live database when the Worker deploys.

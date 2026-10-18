---
bump: major
---
Server logs are deleted after 24 hours. They used to be kept for a week. An hourly job deletes anything older, and the Server Logs page clears it out before it loads, so nothing older than a day ever shows.

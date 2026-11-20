---
name: run-app
description: Run seedkeeper locally against a fake Transmission with realistic torrents, and look at it in a browser with agent-browser. Use when a change shows in the UI (a template, the CSS, what a view lists), when asked to check, screenshot or click through the app, or to reproduce a UI bug.
---

# Run the app

The app runs in dev mode on the JVM against `DemoTransmission`, a fake Transmission whose torrents cover every
verdict, a cross-seed group, a library link, tracker errors and orphans. It needs Java 25 and no Docker.

## Steps

1. Start it: `.claude/skills/run-app/run-app.sh start`, with a Bash timeout of 5 minutes: a cold Maven cache takes
   a few. It returns once the app listens and prints the URL, the login, the logs and the screenshots directory.
   Every start begins from fresh demo data: start again to undo what a run or an override did.
2. Take the **before** screenshots, of every view the change touches, before you edit anything.
3. Make the change. Dev mode picks up an edit to a template, the CSS or Java on the next request: reload the page.
   A change to `application.properties` or the POM needs `stop` and `start`.
4. Take the **after** screenshots of the same views, and click through what changed: open the forms, submit them,
   check a notice or a filter.
5. Stop it: `.claude/skills/run-app/run-app.sh stop`.

Done when every view the change touches has a before and an after screenshot, and every changed control has been used
once in the browser.

## The browser

`agent-browser` drives a headless Chrome; `agent-browser skills get core` is its guide. Point it only at the app's
URL, and give each command its own Bash call: in CI the allowlist matches the start of a command. A session that
logs in and saves a screenshot:

```sh
agent-browser open http://localhost:8187/login
agent-browser find label "Username" fill admin
agent-browser find label "Password" fill ui-password
agent-browser find role button click --name "Log in"
agent-browser open http://localhost:8187/groups
agent-browser screenshot --full /tmp/screenshots/groups-after.png
```

- After logging in, open the view you want by its URL.
- `agent-browser snapshot` gives the page's accessibility tree with refs to click; read a screenshot back with the
  Read tool to see it.
- Check the phone layout with `agent-browser set viewport 390 844` and dark mode with `agent-browser set media dark`.
- `Run now` and `Delete` ask for confirmation: `agent-browser dialog status` shows the dialog, `dialog accept` or
  `dialog dismiss` answers it.

## Screenshots

Save them in the screenshots directory `start` prints, `/tmp/screenshots/` unless `SEEDKEEPER_SCREENSHOTS` says
otherwise, named `<view>-before.png` and `<view>-after.png`. In CI the workflow uploads that directory as the run's
`screenshots` artifact; the pull request links it.

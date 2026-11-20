---
name: run-app
description: Run seedkeeper locally against a fake Transmission with realistic torrents, and look at it in a browser. Use when a change shows in the UI (a template, the CSS, what a view lists), when asked to check, screenshot or click through the app, or to reproduce a UI bug.
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

`.claude/skills/run-app/browser.sh` drives a headless Chrome: it takes agent-browser's commands and flags
(`browser.sh skills get core` is the guide) and keeps the browser on `localhost` and `127.0.0.1`. Give each command
its own Bash call: in CI the allowlist matches the start of a command. A session that logs in and saves a screenshot:

```sh
.claude/skills/run-app/browser.sh open http://localhost:8187/login
.claude/skills/run-app/browser.sh find label "Username" fill admin
.claude/skills/run-app/browser.sh find label "Password" fill ui-password
.claude/skills/run-app/browser.sh find role button click --name "Log in"
.claude/skills/run-app/browser.sh wait --load networkidle
.claude/skills/run-app/browser.sh open http://localhost:8187/groups
.claude/skills/run-app/browser.sh screenshot --full /tmp/screenshots/groups-after.png
```

- After logging in, wait for the login to land (`wait --load networkidle`), then open the view you want by its URL:
  opening it earlier aborts one of the two navigations.
- `snapshot` gives the page's accessibility tree with refs to click; read a screenshot back with the Read tool to see
  it.
- Check the phone layout with `set viewport 390 844` and dark mode with `set media dark`.
- `Run now` and `Delete` ask for confirmation: `dialog status` shows the dialog, `dialog accept` or `dialog dismiss`
  answers it.

## Screenshots

Save them in the screenshots directory `start` prints, `/tmp/screenshots/` unless `SEEDKEEPER_SCREENSHOTS` says
otherwise, named `<view>-before.png` and `<view>-after.png`. In CI the workflow uploads that directory as the run's
`screenshots` artifact; the pull request links it.

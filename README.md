# cann

Android app for building automation pipelines on top of [Composio](https://composio.dev).
A **flow** is a list of Composio tool calls run in order, either manually, **once** at a set time,
**every N minutes**, or **daily** at a time (optionally on chosen weekdays).

- Args are JSON; pull earlier results in with `{{step1.path}}`, `{{prev.path}}`, `{{now}}`, `{{date}}`.
- Apps tab connects accounts via Composio-managed OAuth.
- Runs are scheduled with WorkManager; results show in the Runs tab and as notifications.

Build: `JAVA_HOME=~/.local/opt/jdk-17 ./gradlew assembleDebug`

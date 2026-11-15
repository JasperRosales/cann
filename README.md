# cann

Android app for building automation pipelines on top of [Composio](https://composio.dev).
A **flow** is a list of Composio tool calls run in order, either manually, **once** at a set time,
**every N minutes**, or **daily** at a time (optionally on chosen weekdays).

- Args are JSON; pull earlier results in with `{{step1.path}}`, `{{prev.path}}`, `{{now}}`, `{{date}}`.
- **Build with AI**: describe a flow in plain words and the AI finds the Composio tools and builds the nodes (or edits an open flow). Uses any OpenAI-compatible endpoint; free presets for Gemini, Groq and OpenRouter in Settings.
- Apps tab connects accounts via Composio-managed OAuth.
- Runs are scheduled with WorkManager; results show in the Runs tab and as notifications.

Build: `JAVA_HOME=~/.local/opt/jdk-17 ./gradlew assembleDebug`

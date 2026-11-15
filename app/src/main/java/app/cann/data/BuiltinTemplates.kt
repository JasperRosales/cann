package app.cann.data

/** Starter flows. Fill the blank args (owner, channel, ids…) after picking one. */
object BuiltinTemplates {
    private fun step(slug: String, app: String, note: String, args: String) =
        Step(slug, args.trimIndent(), note, toolkit = app)

    private fun tpl(id: String, name: String, desc: String, schedule: Schedule, vararg steps: Step) =
        Template("builtin:$id", name, desc, Workflow("", name, steps.toList(), schedule), builtIn = true)

    private val weekdays = setOf(1, 2, 3, 4, 5)

    val all = listOf(
        tpl("inbox-slack", "Unread mail → Slack", "Each morning, post how many unread emails came in.",
            Schedule.Daily(8, 0),
            step("GMAIL_FETCH_EMAILS", "gmail", "Fetch unread", """
                {
                  "query": "is:unread newer_than:1d",
                  "max_results": 10
                }"""),
            step("SLACK_SEND_MESSAGE", "slack", "Post count", """
                {
                  "channel": "general",
                  "markdown_text": "📬 {{step1.data.resultSizeEstimate}} unread emails ({{date}})"
                }""")),
        tpl("agenda-mail", "Today's agenda by email", "Email yourself today's calendar events.",
            Schedule.Daily(7, 0, weekdays),
            step("GOOGLECALENDAR_EVENTS_LIST", "googlecalendar", "Upcoming events", """
                {
                  "calendarId": "primary",
                  "timeMin": "{{now}}",
                  "maxResults": 10,
                  "singleEvents": true,
                  "orderBy": "startTime"
                }"""),
            step("GMAIL_SEND_EMAIL", "gmail", "Send agenda", """
                {
                  "recipient_email": "",
                  "subject": "Agenda for {{date}}",
                  "body": "{{step1.data.items}}"
                }""")),
        tpl("issues-notion", "GitHub issues → Notion", "Weekly snapshot of open issues in a Notion page.",
            Schedule.Daily(9, 0, setOf(1)),
            step("GITHUB_LIST_REPOSITORY_ISSUES", "github", "Open issues", """
                {
                  "owner": "",
                  "repo": "",
                  "state": "open",
                  "per_page": 20
                }"""),
            step("NOTION_CREATE_NOTION_PAGE", "notion", "Write page", """
                {
                  "parent_id": "",
                  "title": "Open issues · {{date}}",
                  "markdown": "{{step1.data}}"
                }""")),
        tpl("sheet-log", "Log to Google Sheets", "Append a timestamped row every hour.",
            Schedule.Interval(60),
            step("GOOGLESHEETS_SPREADSHEETS_VALUES_APPEND", "googlesheets", "Append row", """
                {
                  "spreadsheetId": "",
                  "range": "Sheet1!A1",
                  "valueInputOption": "USER_ENTERED",
                  "values": [["{{now}}", "ping"]]
                }""")),
        tpl("standup", "Standup reminder", "Ping a Slack channel on weekday mornings.",
            Schedule.Daily(9, 30, weekdays),
            step("SLACK_SEND_MESSAGE", "slack", "Remind", """
                {
                  "channel": "general",
                  "markdown_text": "⏰ Standup in 5 minutes!"
                }""")),
    )
}

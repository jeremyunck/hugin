---
name: google-docs-sheets
description: Use when the user asks Hugin to create, read, or edit Google Docs or Google Sheets (e.g. "make a doc", "add a row to my spreadsheet", "read that Google Sheet", "summarise this doc"). Covers the google_docs_* and google_sheets_* built-in tools, how authentication/sharing works, and the right tool for each editing operation.
---

# Google Docs & Sheets

Hugin has seven built-in tools for Google Workspace, backed by the official Google API Java
client libraries. They run in-process in the `mcp-integration` server (no MCP server needed).

| Tool | Purpose |
| --- | --- |
| `google_docs_create` | Create a new Doc (optional title + Markdown body rendered into Docs formatting). |
| `google_docs_read` | Read a Doc's plain-text content. |
| `google_docs_edit` | Append / insert / replace text in a Doc. |
| `google_sheets_create` | Create a new spreadsheet. |
| `google_sheets_read` | Read cell values from a range. |
| `google_sheets_write` | Overwrite cells in a range. |
| `google_sheets_append` | Add new rows after the existing data. |

## Identifiers

Every read/edit tool accepts **either a bare id or a full Google URL** in `document_id` /
`spreadsheet_id` — paste whatever the user gives you. The id is the part after `/d/` in a URL like
`https://docs.google.com/document/d/<id>/edit`. Create tools return both the id and the URL; hand
the URL back to the user.

## Authentication & sharing (important)

The tools can authenticate either as a **Google user via OAuth** or as a **service account**.
OAuth is the preferred setup for a personal install; service accounts are still supported for
Workspace/domain-wide delegation.

OAuth has one important consequence:

- On first use, Hugin opens a browser for consent and caches refresh tokens in `google.oauth-token-dir`.
- Files Hugin creates belong to the authenticated Google user, so `share_with` is only needed when you
  want to share the new doc/sheet with someone else.

Service account auth has two consequences you must keep in mind:

- **To read or edit an existing file**, that file must be shared with the service account's email
  address (the `client_email` in the credentials JSON). If a call fails with a 403/404, the most
  likely cause is that the file hasn't been shared with the service account — tell the user to share
  it (or that it doesn't exist), don't keep retrying.
- **Files the tools create are owned by the service account**, so a human won't see them in their
  Drive unless they're shared. When creating a doc/sheet *for a person*, pass `share_with` with their
  email so they can open it. If `google.default-share-with` is configured, creation shares with that
  address automatically and you can omit `share_with`.

If the tools return a message saying they are "unavailable", credentials aren't configured — relay
that to the user; it is a setup step, not something to work around.

## Creating

`google_docs_create` treats `text` as Markdown and renders common structure cleanly in Docs:

```text
google_docs_create   { "title": "Q3 Notes", "text": "# Heading\n- Bullet\n\n> Quote", "share_with": "user@example.com" }
google_sheets_create { "title": "Budget 2026", "share_with": "user@example.com" }
```

Both return the id and URL. For a sheet you usually create it, then `google_sheets_write` /
`google_sheets_append` to populate it.

## Reading

```
google_docs_read   { "document_id": "<id-or-url>" }
google_sheets_read { "spreadsheet_id": "<id-or-url>", "range": "Sheet1!A1:D20" }
```

`range` is A1 notation. Pass just a tab name (e.g. `"Sheet1"`) to read the whole tab. Sheet rows come
back tab-separated, one row per line.

## Editing a Doc — pick the right operation

`google_docs_edit` takes an `operation`:

- **`append`** — add `text` to the end of the document. Use for "add a paragraph", "note this at the
  bottom". Include a leading `\n` if you want it on a new line.
- **`insert`** — insert `text` at a 1-based character `index`. Use only when you know the index
  (e.g. inserting at the very top uses `index: 1`).
- **`replace`** — replace every occurrence of `find` with `text` (set `match_case: false` for
  case-insensitive). Use for "change X to Y", "fix this wording", or targeted edits. To delete text,
  replace it with an empty `text`.

```
google_docs_edit { "document_id": "<id>", "operation": "append",  "text": "\nNew section." }
google_docs_edit { "document_id": "<id>", "operation": "replace", "find": "TODO", "text": "Done" }
```

To restructure a doc, prefer reading it first (`google_docs_read`) so your `find` strings match the
actual content, then apply `replace` edits.

## Editing a Sheet — write vs append

- **`google_sheets_write`** overwrites a range starting at the anchor cell. Use it to set/replace
  specific cells, headers, or a known block. `range` is the top-left anchor (e.g. `"Sheet1!A1"`) or a
  full range.
- **`google_sheets_append`** adds rows *after* the last row of existing data — it never overwrites.
  Use it to log entries or grow a table. `range` selects the table/tab (e.g. `"Sheet1"`).

`values` is always a **2-D array of rows**. Values are entered as if typed (`USER_ENTERED`), so
`"=SUM(A1:A3)"` becomes a formula and `36` becomes a number.

```
google_sheets_write  { "spreadsheet_id": "<id>", "range": "Sheet1!A1",
                       "values": [["Name","Age"],["Ada",36]] }
google_sheets_append { "spreadsheet_id": "<id>", "range": "Sheet1",
                       "values": [["Alan",41],["Grace",45]] }
```

## Workflow tips

- Confirm the target: if the user says "my budget sheet" without a link, ask for the URL/id rather
  than guessing.
- After creating a file, always report the URL back to the user.
- For "add a row" requests, use `append` (not `write`) so you don't clobber existing data.
- For "update cell B2" or "set the header row", use `write` with a precise `range`.
- If a read returns empty, the range may be wrong (check the tab name) before assuming the file is empty.

## Configuration reference

Configured under the `google:` prefix in `mcp-integration`'s `application.yml`:

- `google.oauth-client-secrets-file` (`GOOGLE_OAUTH_CLIENT_SECRETS_FILE`) — path to the OAuth client-secrets JSON.
- `google.oauth-token-dir` (`GOOGLE_OAUTH_TOKEN_DIR`) — directory where OAuth refresh tokens are cached.
- `google.oauth-local-server-port` (`GOOGLE_OAUTH_LOCAL_SERVER_PORT`) — local loopback port for the OAuth callback.
- `google.credentials-file` (`GOOGLE_APPLICATION_CREDENTIALS`) — path to the service-account JSON key.
- `google.application-name` — name reported to Google APIs.
- `google.impersonate-user` (`GOOGLE_IMPERSONATE_USER`) — for Workspace domain-wide delegation.
- `google.default-share-with` (`GOOGLE_DEFAULT_SHARE_WITH`) — email new files are auto-shared with.

Setup:

- OAuth: enable the Docs, Sheets and Drive APIs, create a desktop OAuth client, download the JSON,
  point `GOOGLE_OAUTH_CLIENT_SECRETS_FILE` at it, and complete the one-time browser consent flow.
- Service account: enable the Docs, Sheets and Drive APIs, create a service account, download its JSON
  key, point `GOOGLE_APPLICATION_CREDENTIALS` at it, and share target files with the service account's
  `client_email`.

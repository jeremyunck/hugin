---
name: google-gmail
description: Use when the user asks Hugin to inspect, search, read, or send Gmail emails. Covers the google_gmail_search, google_gmail_read, and google_gmail_send built-in tools, Gmail query syntax, and OAuth/service-account setup.
---

# Google Gmail

Hugin has three built-in Gmail tools for working with the authenticated account.

| Tool | Purpose |
| --- | --- |
| `google_gmail_search` | Search Gmail with query syntax and return matching messages with metadata and snippets. |
| `google_gmail_read` | Read a specific message in detail, including headers and body text. |
| `google_gmail_send` | Send a new email or reply to an existing Gmail message/thread. |

## How to use them

- Use `google_gmail_search` first when the user says "check my inbox", "look through my emails", or "find mail from X".
- Search results include `id` and `threadId`. Pass the `id` into `google_gmail_read` for the message itself.
- Gmail query syntax is the same as Gmail search in the web UI, for example `from:alice@example.com newer_than:7d is:unread`.
- If the user does not provide a query, search defaults to `in:inbox`.
- Use `google_gmail_send` when the user wants a reply, a follow-up, or a new outbound email.
- For replies, prefer passing `reply_to_message_id` and the reply body. Hugin can infer the recipient, thread, and subject from the original message.

## Authentication

The Gmail tools use the same Google Workspace configuration as Docs and Sheets.

- Personal installs should use OAuth with `GOOGLE_OAUTH_CLIENT_SECRETS_FILE`.
- Workspace setups can use a service account with domain-wide delegation and `GOOGLE_APPLICATION_CREDENTIALS`.
- The Google Cloud project must have the Gmail API enabled in addition to the other Google APIs.
- Sending mail requires Gmail send scope in addition to read scope. After you expand scopes, re-consent so the new permissions take effect.

## Practical notes

- Search is read-only. `google_gmail_send` writes mail, but only after the Gmail write scope is granted.
- Message bodies are returned as plain text when possible; if a message is HTML-only, Hugin falls back to a readable text version.
- If the tools report themselves as unavailable, Google credentials have not been configured.

## Workflow recipes

- Inbox triage:
  - Search `is:unread in:inbox` or a narrower query like `from:boss@example.com newer_than:7d`.
  - Read the most relevant messages in full.
  - Summarize the actionable items and decide whether they need a reply, a task, or no action.
- Reply workflow:
  - Read the target message first.
  - Use `google_gmail_send` with `reply_to_message_id` and the reply body.
  - Let Hugin infer the thread, subject, and recipient unless you need to override them.
- Follow-up workflow:
  - Search for old unresolved mail such as `is:unread older_than:7d` or `label:important older_than:14d`.
  - Read the remaining candidates.
  - Send a concise follow-up or ask for a draft before sending if the message needs human approval.

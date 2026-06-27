# Local Stock Tracker Recipe

This recipe is the local, non-LLM path for a request like:

- track a list of tickers
- fetch data multiple times per day with `yfinance`
- store the results in a SQL database
- run the collector from cron on the same machine

It is intentionally deterministic. The cron job runs a Python script directly; it does not call
Bouw's `schedule_prompt` tool and it does not require the LLM.

## What this bridges

Bouw already has:

- local file editing tools
- local shell execution
- a workspace boundary for generated files

The missing pieces for this use case are:

- a clear local-only pattern for recurring jobs
- a repeatable Python + SQLite scaffold
- a cron wrapper that invokes the script without any agent involvement

## Layout

Use a project directory in the agent workspace, for example:

```text
stock-tracker/
  config.json
  requirements.txt
  run.sh
  install_cron.sh
  schema.sql
  stock_tracker.py
```

## Setup

1. Copy `config.example.json` to `config.json`.
2. Edit the ticker list and database path.
3. Run `./setup.sh` once to create the Python virtual environment and install dependencies.
4. Run `./run.sh --config config.json` once to create the database and verify the feed.
5. Run `./install_cron.sh` to add the scheduled jobs.

## Scheduling

Cron does not have a native "10 times per day" primitive, so this recipe uses explicit run times.
That keeps the schedule deterministic and easy to inspect. If you want a different cadence, change
the `cron_times` list in `config.json`.

## Notes

- SQLite is the default because it is local, durable, and needs no server.
- `yfinance` is a best-effort data source. Some tickers or fields may be unavailable at times.
- The script should be allowed to fail fast on missing data rather than silently invent values.
- If you need a shared DB or larger write volume, the same pattern can be swapped to Postgres later.

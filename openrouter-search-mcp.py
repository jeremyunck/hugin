#!/usr/bin/env python3
"""
MCP server that provides web search via OpenRouter's Perplexity sonar model.
Used as a fallback when DuckDuckGo is blocked by bot detection in cloud environments.
"""

import asyncio
import json
import os
import sys
import urllib.request
import urllib.error

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp import types

OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
SEARCH_MODEL = "perplexity/sonar"

# Fail fast on startup if the API key is missing.
if not OPENROUTER_API_KEY:
    print("ERROR: OPENROUTER_API_KEY environment variable is not set", file=sys.stderr)
    sys.exit(1)

app = Server("openrouter-search")


def _call_openrouter(query: str) -> str:
    """Call OpenRouter with a Perplexity sonar model to perform web search."""
    payload = json.dumps({
        "model": SEARCH_MODEL,
        "messages": [
            {
                "role": "user",
                "content": (
                    f"Search the web and provide the latest information about: {query}\n\n"
                    "Return a concise summary of the most relevant and recent results."
                ),
            }
        ],
        "max_tokens": 1024,
    }).encode("utf-8")

    req = urllib.request.Request(
        f"{OPENROUTER_BASE_URL}/chat/completions",
        data=payload,
        headers={
            "Authorization": f"Bearer {OPENROUTER_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenRouter API error {e.code}: {body}") from e
    except (KeyError, IndexError) as e:
        raise RuntimeError(f"Unexpected OpenRouter response structure: {e}") from e


@app.list_tools()
async def list_tools() -> list[types.Tool]:
    return [
        types.Tool(
            name="web_search",
            description=(
                "Search the web for current information, news, and recent events. "
                "Returns a summary of the most relevant and up-to-date search results."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search query",
                    }
                },
                "required": ["query"],
            },
        )
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent]:
    if name != "web_search":
        raise ValueError(f"Unknown tool: {name}")

    query = arguments.get("query", "")
    if not query:
        raise ValueError("query parameter is required")

    loop = asyncio.get_event_loop()
    try:
        result = await loop.run_in_executor(None, _call_openrouter, query)
    except RuntimeError as e:
        return [types.TextContent(type="text", text=f"Search failed: {e}")]

    return [types.TextContent(type="text", text=result)]


async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())

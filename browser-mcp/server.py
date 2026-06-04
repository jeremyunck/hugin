#!/usr/bin/env python3
"""MCP server that provides browser automation and simple multimodal helpers."""

import asyncio
import io
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from mcp import types
from mcp.server import Server
from mcp.server.stdio import stdio_server
from playwright.async_api import async_playwright

try:
    import pytesseract
    from PIL import Image
except Exception:  # pragma: no cover - optional dependency
    pytesseract = None
    Image = None

app = Server("hugin-browser")


@dataclass
class BrowserState:
    browser: object | None = None
    context: object | None = None
    page: object | None = None
    last_screenshot: str | None = None


STATE = BrowserState()


async def ensure_page():
    if STATE.page is not None:
        return STATE.page
    playwright = await async_playwright().start()
    browser = await playwright.chromium.launch(headless=True)
    context = await browser.new_context(viewport={"width": 1440, "height": 1024})
    page = await context.new_page()
    STATE.browser = browser
    STATE.context = context
    STATE.page = page
    return page


async def close_browser():
    if STATE.page is not None:
        await STATE.context.close()
        await STATE.browser.close()
    STATE.browser = None
    STATE.context = None
    STATE.page = None


def _trim(text: str, limit: int = 8000) -> str:
    text = text.strip()
    return text if len(text) <= limit else text[:limit] + "\n... [truncated]"


def _parse_selector(arguments: dict) -> str:
    selector = arguments.get("selector", "")
    text = arguments.get("text", "")
    if selector:
        return selector
    if text:
        return f"text={text}"
    raise ValueError("Either selector or text is required")


def _ocr_image(path: str) -> str:
    if pytesseract is None or Image is None:
        return "OCR unavailable: pytesseract/Pillow is not installed."
    image = Image.open(path)
    return pytesseract.image_to_string(image).strip()


@app.list_tools()
async def list_tools() -> list[types.Tool]:
    return [
        types.Tool(
            name="browser_open",
            description="Open a URL in a headless browser and return page metadata and text.",
            inputSchema={
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "URL to open"}
                },
                "required": ["url"],
            },
        ),
        types.Tool(
            name="browser_click",
            description="Click an element using a CSS selector or text locator.",
            inputSchema={
                "type": "object",
                "properties": {
                    "selector": {"type": "string", "description": "CSS selector"},
                    "text": {"type": "string", "description": "Text locator"},
                },
                "required": [],
            },
        ),
        types.Tool(
            name="browser_type",
            description="Fill an input or textarea by selector or text locator.",
            inputSchema={
                "type": "object",
                "properties": {
                    "selector": {"type": "string"},
                    "text": {"type": "string", "description": "Text to type into the field."},
                    "clear": {"type": "boolean", "description": "Clear the field first", "default": True},
                },
                "required": ["text"],
            },
        ),
        types.Tool(
            name="browser_press",
            description="Press a key on the focused page or a specific element.",
            inputSchema={
                "type": "object",
                "properties": {
                    "selector": {"type": "string"},
                    "key": {"type": "string", "description": "Key to press, e.g. Enter or Control+A"},
                },
                "required": ["key"],
            },
        ),
        types.Tool(
            name="browser_extract",
            description="Extract visible text from the page or a selected element.",
            inputSchema={
                "type": "object",
                "properties": {
                    "selector": {"type": "string", "description": "Optional CSS selector."},
                    "max_chars": {"type": "integer", "description": "Maximum characters to return."},
                },
                "required": [],
            },
        ),
        types.Tool(
            name="browser_screenshot",
            description="Save a screenshot of the current page and return the file path.",
            inputSchema={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Optional output path."}
                },
                "required": [],
            },
        ),
        types.Tool(
            name="ocr_image",
            description="Run OCR against an image file or the last browser screenshot.",
            inputSchema={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Image file path."}
                },
                "required": [],
            },
        ),
        types.Tool(
            name="transcribe_audio",
            description="Transcribe an audio file using whisper if installed.",
            inputSchema={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Audio file path."}
                },
                "required": ["path"],
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict) -> list[types.TextContent]:
    page = await ensure_page()

    if name == "browser_open":
        url = arguments.get("url", "").strip()
        if not url:
            raise ValueError("url is required")
        await page.goto(url, wait_until="networkidle")
        text = await page.locator("body").inner_text()
        return [types.TextContent(type="text", text=_trim(f"URL: {page.url}\nTitle: {await page.title()}\n\n{text}"))]

    if name == "browser_click":
        locator = _parse_selector(arguments)
        await page.locator(locator).first.click(timeout=15000)
        await page.wait_for_load_state("networkidle")
        return [types.TextContent(type="text", text=f"Clicked {locator}. Current URL: {page.url}")]

    if name == "browser_type":
        locator = _parse_selector(arguments)
        text = arguments.get("text", "")
        clear = bool(arguments.get("clear", True))
        if clear:
            await page.locator(locator).fill(text)
        else:
            await page.locator(locator).type(text)
        return [types.TextContent(type="text", text=f"Filled {locator}.")]

    if name == "browser_press":
        key = arguments.get("key", "").strip()
        if not key:
            raise ValueError("key is required")
        selector = arguments.get("selector", "").strip()
        if selector:
            await page.locator(selector).press(key)
        else:
            await page.keyboard.press(key)
        return [types.TextContent(type="text", text=f"Pressed {key}.")]

    if name == "browser_extract":
        selector = arguments.get("selector", "").strip() or "body"
        max_chars = int(arguments.get("max_chars", 8000) or 8000)
        text = await page.locator(selector).inner_text()
        return [types.TextContent(type="text", text=_trim(text, max_chars))]

    if name == "browser_screenshot":
        output = arguments.get("path", "").strip()
        if not output:
            fd, output = tempfile.mkstemp(prefix="hugin-browser-", suffix=".png")
            os.close(fd)
        await page.screenshot(path=output, full_page=True)
        STATE.last_screenshot = output
        return [types.TextContent(type="text", text=f"Screenshot saved to {output}")]

    if name == "ocr_image":
        path = arguments.get("path", "").strip() or STATE.last_screenshot
        if not path:
            return [types.TextContent(type="text", text="No image path provided and no screenshot available.")]
        return [types.TextContent(type="text", text=_trim(_ocr_image(path)))]

    if name == "transcribe_audio":
        path = arguments.get("path", "").strip()
        if not path:
            raise ValueError("path is required")
        whisper = shutil.which("whisper")
        if whisper:
            with tempfile.TemporaryDirectory() as tmpdir:
                output = subprocess.check_output(
                    [whisper, path, "--model", "base", "--output_dir", tmpdir, "--output_format", "txt"],
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=600,
                )
                txt_files = list(Path(tmpdir).glob("*.txt"))
                transcript = txt_files[0].read_text(encoding="utf-8") if txt_files else output
                return [types.TextContent(type="text", text=_trim(transcript))]
        return [types.TextContent(type="text", text="Audio transcription unavailable: whisper is not installed.")]

    raise ValueError(f"Unknown tool: {name}")


async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())

#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlparse


def find_free_port():
    sock = socket.socket()
    try:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]
    finally:
        sock.close()


def read_exact(sock, size):
    chunks = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise RuntimeError("websocket closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


class WebSocket:
    def __init__(self, url):
        parsed = urlparse(url)
        self.sock = socket.create_connection((parsed.hostname, parsed.port), timeout=5)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        path = parsed.path
        if parsed.query:
            path += "?" + parsed.query
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {parsed.hostname}:{parsed.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode("ascii"))
        response = b""
        while b"\r\n\r\n" not in response:
            response += self.sock.recv(4096)
        if b" 101 " not in response.split(b"\r\n", 1)[0]:
            raise RuntimeError(f"websocket upgrade failed: {response[:120]!r}")

    def send_text(self, text):
        payload = text.encode("utf-8")
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", length))
        mask = os.urandom(4)
        header.extend(mask)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv_text(self):
        while True:
            first, second = read_exact(self.sock, 2)
            opcode = first & 0x0F
            masked = second & 0x80
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", read_exact(self.sock, 2))[0]
            elif length == 127:
                length = struct.unpack("!Q", read_exact(self.sock, 8))[0]
            mask = read_exact(self.sock, 4) if masked else b""
            payload = read_exact(self.sock, length)
            if masked:
                payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
            if opcode == 1:
                return payload.decode("utf-8")
            if opcode == 8:
                raise RuntimeError("websocket closed by peer")
            if opcode == 9:
                self.sock.sendall(b"\x8a\x00")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class Cdp:
    def __init__(self, websocket_url):
        self.ws = WebSocket(websocket_url)
        self.next_id = 1

    def call(self, method, params=None, timeout_seconds=10):
        msg_id = self.next_id
        self.next_id += 1
        self.ws.send_text(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            message = json.loads(self.ws.recv_text())
            if message.get("id") != msg_id:
                continue
            if "error" in message:
                raise RuntimeError(f"CDP {method} failed: {message['error']}")
            return message.get("result") or {}
        raise TimeoutError(f"timed out waiting for CDP {method}")

    def close(self):
        self.ws.close()


STATE_EXPR = r"""
(() => {
  const text = (id) => (document.getElementById(id)?.textContent || '').trim();
  const rows = Array.from(document.querySelectorAll('#market-list [data-symbol]'));
  const canvases = Array.from(document.querySelectorAll('canvas')).map((canvas) => {
    const rect = canvas.getBoundingClientRect();
    return { width: canvas.width, height: canvas.height, rectWidth: rect.width, rectHeight: rect.height };
  });
  const quoteStatus = text('quote-update-health');
  const statusLine = text('status-line');
  const historyMatch = statusLine.match(/Rendered\s+(\d+)\s+bar\(s\)/);
  return {
    title: document.body?.innerText.includes('Kalshi Product Dashboard') || false,
    marketSearch: !!document.getElementById('market-search'),
    marketStatusFilter: !!document.getElementById('market-status-filter'),
    marketSearchApply: !!document.getElementById('market-search-apply'),
    marketState: text('market-state'),
    marketRows: rows.length,
    selectedRows: document.querySelectorAll('#market-list .selected').length,
    marketEmpty: document.body?.innerText.includes('No markets indexed yet') ||
      document.body?.innerText.includes('No markets match') || false,
    chartCanvas: canvases.some((canvas) =>
      canvas.width > 0 && canvas.height > 0 && canvas.rectWidth > 0 && canvas.rectHeight > 0),
    quoteStripText: document.getElementById('quote-strip')?.innerText || '',
    featureRows: document.querySelectorAll('.feature-row').length,
    featureEmpty: document.body?.innerText.toLowerCase().includes('no feature outputs') || false,
    adapterHealth: text('adapter-health'),
    releaseIdentity: text('release-identity'),
    healthDataAge: text('health-data-age'),
    refreshHealth: text('refresh-health'),
    featureplantHealth: text('featureplant-health'),
    freshnessState: text('freshness-state'),
    quoteUpdateHealth: quoteStatus,
    statusLine,
    historyBars: historyMatch ? Number(historyMatch[1]) : 0,
    productMarketPanel: !!document.getElementById('product-market-panel'),
    researchFeaturesPanel: !!document.getElementById('research-features-panel'),
    runtimeOperatorPanel: !!document.getElementById('runtime-operator-panel'),
    latencyFreshnessPanel: !!document.getElementById('latency-freshness-panel'),
    operatorPlanPanel: !!document.getElementById('operator-plan-panel'),
    operatorPlanOutput: text('operator-env-plan'),
    noHorizontalOverflow: document.documentElement.scrollWidth <= window.innerWidth,
    quoteFeedVisible: /(SSE (connected|snapshot|changed)|long-poll (changed|timeout|fallback)|fallback polling)/.test(quoteStatus) ||
      /SSE req\s+\d+\s+\/ events\s+[1-9]\d*/.test(quoteStatus) ||
      /long-poll req\s+[1-9]\d*/.test(quoteStatus),
    quoteError: /(SSE|long-poll) error/.test(quoteStatus),
    loading: document.body?.innerText.includes('(loading...)') || false,
    lightweightChartsMissing: document.body?.innerText.includes('LightweightCharts is not defined') || false,
  };
})()
"""


INTERACTION_EXPR = r"""
(() => {
  const firstRow = document.querySelector('#market-list [data-symbol]');
  if (firstRow) {
    firstRow.click();
  }
  const symbol = firstRow?.dataset?.symbol || '';
  const token = symbol.includes('-') ? symbol.split('-')[0] : symbol.slice(0, 8);
  const search = document.getElementById('market-search');
  if (search && token) {
    search.value = token;
    search.dispatchEvent(new Event('input', { bubbles: true }));
  }
  const rowText = (firstRow?.textContent || '').toLowerCase();
  const status = document.getElementById('market-status-filter');
  if (status && rowText.includes('open')) {
    status.value = 'open';
    status.dispatchEvent(new Event('change', { bubbles: true }));
  }
  const apply = document.getElementById('market-search-apply');
  if (apply) {
    apply.click();
  }
  return { token, status: status?.value || '', clicked: !!firstRow };
})()
"""


def evaluate(cdp, expression):
    result = cdp.call(
        "Runtime.evaluate",
        {"expression": expression, "returnByValue": True, "awaitPromise": True},
    )
    remote = result.get("result") or {}
    if "value" in remote:
        return remote["value"]
    return None


def wait_for_state(cdp, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    last = None
    while time.monotonic() < deadline:
        last = evaluate(cdp, STATE_EXPR) or {}
        ready = (
            last.get("title")
            and last.get("marketSearch")
            and last.get("marketStatusFilter")
            and last.get("marketSearchApply")
            and (last.get("marketRows", 0) > 0 or last.get("marketEmpty"))
            and last.get("chartCanvas")
            and not last.get("loading")
            and last.get("adapterHealth") not in ("", "-")
            and last.get("releaseIdentity") not in ("", "-")
            and last.get("healthDataAge") not in ("", "-")
            and last.get("quoteUpdateHealth") not in ("", "-")
            and last.get("productMarketPanel")
            and last.get("researchFeaturesPanel")
            and last.get("runtimeOperatorPanel")
            and last.get("latencyFreshnessPanel")
            and last.get("operatorPlanPanel")
            and last.get("operatorPlanOutput") not in ("", "-")
            and last.get("noHorizontalOverflow")
            and last.get("freshnessState") in ("waiting", "fresh", "stale")
            and last.get("quoteFeedVisible")
            and not last.get("quoteError")
            and not last.get("lightweightChartsMissing")
        )
        if ready:
            return last
        time.sleep(0.25)
    raise TimeoutError(f"dashboard did not reach browser smoke ready state: {json.dumps(last, sort_keys=True)}")


def wait_for_history_bars(cdp, timeout_seconds, expected_minimum):
    if expected_minimum <= 0:
        return 0
    deadline = time.monotonic() + timeout_seconds
    last = None
    while time.monotonic() < deadline:
        last = evaluate(cdp, STATE_EXPR) or {}
        bars = int(last.get("historyBars") or 0)
        if bars >= expected_minimum:
            return bars
        time.sleep(0.25)
    raise TimeoutError(
        f"dashboard rendered {int((last or {}).get('historyBars') or 0)} history bars; "
        f"expected at least {expected_minimum}: {json.dumps(last, sort_keys=True)}"
    )


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--browser", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--dom-file", required=True)
    parser.add_argument("--screenshot-file", required=True)
    parser.add_argument("--browser-log", required=True)
    parser.add_argument("--profile-dir", required=True)
    parser.add_argument("--timeout-seconds", type=int, required=True)
    args = parser.parse_args()

    Path(args.profile_dir).mkdir(parents=True, exist_ok=True)
    Path(args.dom_file).parent.mkdir(parents=True, exist_ok=True)
    Path(args.screenshot_file).parent.mkdir(parents=True, exist_ok=True)
    port = find_free_port()
    chrome_args = [
        args.browser,
        "--headless=new",
        "--disable-gpu",
        "--disable-background-networking",
        "--disable-component-update",
        "--no-sandbox",
        "--no-first-run",
        "--disable-dev-shm-usage",
        "--window-size=1440,1000",
        f"--user-data-dir={args.profile_dir}",
        "--remote-debugging-address=127.0.0.1",
        f"--remote-debugging-port={port}",
        "about:blank",
    ]
    with open(args.browser_log, "ab") as log:
        process = subprocess.Popen(chrome_args, stdout=subprocess.DEVNULL, stderr=log)
    cdp = None
    try:
        deadline = time.monotonic() + min(10, args.timeout_seconds)
        targets = None
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=1) as response:
                    targets = json.loads(response.read().decode("utf-8"))
                break
            except Exception:
                time.sleep(0.1)
        if not targets:
            raise TimeoutError("browser devtools endpoint did not start")
        page = next((target for target in targets if target.get("type") == "page"), targets[0])
        cdp = Cdp(page["webSocketDebuggerUrl"])
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
        cdp.call("Page.navigate", {"url": args.url})
        wait_for_state(cdp, args.timeout_seconds)
        interaction = evaluate(cdp, INTERACTION_EXPR) or {}
        time.sleep(0.5)
        state = wait_for_state(cdp, args.timeout_seconds)
        expected_history_bars = int(os.environ.get("FRONTEND_BROWSER_SMOKE_EXPECTED_HISTORY_BARS_MIN") or "0")
        state["historyBars"] = wait_for_history_bars(cdp, args.timeout_seconds, expected_history_bars)
        interaction["selectedRowsAfter"] = state.get("selectedRows", 0)
        html = evaluate(cdp, "document.documentElement.outerHTML") or ""
        if not html:
            raise RuntimeError("empty dashboard DOM")
        with open(args.dom_file, "w", encoding="utf-8") as handle:
            handle.write(html)
        screenshot = cdp.call("Page.captureScreenshot", {"format": "png", "fromSurface": True})
        with open(args.screenshot_file, "wb") as handle:
            handle.write(base64.b64decode(screenshot["data"]))
        print(json.dumps({
            "state": state,
            "interaction": interaction,
            "dom_sha256": sha256(args.dom_file),
            "screenshot_sha256": sha256(args.screenshot_file),
        }, sort_keys=True))
    finally:
        if cdp is not None:
            cdp.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"frontend browser CDP smoke failed: {exc}", file=sys.stderr)
        raise

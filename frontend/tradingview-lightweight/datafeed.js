const chartElement = document.querySelector("#chart");
const volumeElement = document.querySelector("#volume");
const adapterInput = document.querySelector("#adapter");
const symbolInput = document.querySelector("#symbol");
const resolutionInput = document.querySelector("#resolution");
const symbolList = document.querySelector("#symbols");
const modeElement = document.querySelector("#mode");
const bidElement = document.querySelector("#bid");
const askElement = document.querySelector("#ask");
const eventsElement = document.querySelector("#events");
const messageElement = document.querySelector("#message");

let activeSymbol = "";
let eventCount = 0;
let socket = null;

adapterInput.value = defaultAdapterUrl();

const chart = LightweightCharts.createChart(chartElement, {
  layout: { background: { color: "#ffffff" }, textColor: "#17202a" },
  rightPriceScale: { borderColor: "#d9e1e8" },
  timeScale: { borderColor: "#d9e1e8", timeVisible: true, secondsVisible: true },
  grid: { vertLines: { color: "#eef3f6" }, horzLines: { color: "#eef3f6" } },
  crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
});

const volumeChart = LightweightCharts.createChart(volumeElement, {
  layout: { background: { color: "#ffffff" }, textColor: "#5f6f7d" },
  rightPriceScale: { borderColor: "#d9e1e8" },
  timeScale: { borderColor: "#d9e1e8", timeVisible: true, secondsVisible: true },
  grid: { vertLines: { color: "#f4f7f9" }, horzLines: { color: "#f4f7f9" } },
});

const candles = chart.addCandlestickSeries({
  upColor: "#0f766e",
  downColor: "#b42318",
  borderVisible: false,
  wickUpColor: "#0f766e",
  wickDownColor: "#b42318",
});
const volume = volumeChart.addHistogramSeries({ priceFormat: { type: "volume" }, color: "#6b8797" });

window.addEventListener("resize", resizeCharts);
document.querySelector("#load").addEventListener("click", loadSelectedSymbol);
document.querySelector("#replay").addEventListener("click", startReplay);
symbolInput.addEventListener("change", loadSelectedSymbol);
adapterInput.addEventListener("change", () => {
  loadSymbols();
  connectStream();
});

resizeCharts();
loadSymbols().then(() => {
  const first = symbolList.querySelector("option");
  if (first && !symbolInput.value) {
    symbolInput.value = first.value;
  }
  loadSelectedSymbol();
  connectStream();
});

function resizeCharts() {
  chart.applyOptions({ width: chartElement.clientWidth, height: chartElement.clientHeight });
  volumeChart.applyOptions({ width: volumeElement.clientWidth, height: volumeElement.clientHeight });
}

async function loadSymbols() {
  try {
    const response = await fetch(adapterUrl("/symbols"));
    const body = await response.json();
    symbolList.innerHTML = "";
    for (const item of body.symbols || []) {
      const option = document.createElement("option");
      option.value = item.symbol;
      symbolList.append(option);
    }
    setMessage(`${(body.symbols || []).length} symbols indexed`);
  } catch (error) {
    setMessage(`Adapter unavailable: ${error.message}`, true);
  }
}

async function loadSelectedSymbol() {
  activeSymbol = symbolInput.value.trim();
  if (!activeSymbol) {
    return;
  }
  const now = Math.floor(Date.now() / 1000);
  const from = now - 24 * 60 * 60;
  const url = adapterUrl(`/history?symbol=${encodeURIComponent(activeSymbol)}&resolution=${encodeURIComponent(resolutionInput.value)}&from=${from}&to=${now}&limit=1000`);
  const response = await fetch(url);
  const body = await response.json();
  const bars = (body.bars || []).map(toChartBar);
  candles.setData(bars);
  volume.setData((body.bars || []).map(toVolumeBar));
  chart.timeScale().fitContent();
  volumeChart.timeScale().fitContent();
  refreshQuote();
  setMessage(`${bars.length} bars loaded`);
}

async function refreshQuote() {
  if (!activeSymbol) {
    return;
  }
  const response = await fetch(adapterUrl(`/quotes?symbols=${encodeURIComponent(activeSymbol)}`));
  const body = await response.json();
  const quote = (body.quotes || [])[0] || {};
  bidElement.textContent = numberOrDash(quote.top_bid ?? quote.bid);
  askElement.textContent = numberOrDash(quote.top_ask ?? quote.ask);
}

function connectStream() {
  if (socket) {
    socket.close();
  }
  const wsUrl = websocketUrl("/stream");
  socket = new WebSocket(wsUrl);
  socket.onopen = () => setMessage("stream connected");
  socket.onclose = () => setMessage("stream disconnected", true);
  socket.onerror = () => setMessage("stream error", true);
  socket.onmessage = event => {
    eventCount += 1;
    eventsElement.textContent = eventCount;
    const envelope = JSON.parse(event.data);
    const item = envelope.event;
    if (!item || item.metadata?.market_ticker !== activeSymbol) {
      return;
    }
    if (item.event_type === "ticker_update" || item.event_type === "market_trade") {
      loadSelectedSymbol();
    }
    if (item.event_type === "top_of_book_update") {
      bidElement.textContent = numberOrDash(micros(item.bid_price_micros));
      askElement.textContent = numberOrDash(micros(item.ask_price_micros));
    }
    if (envelope.type === "replay_event") {
      modeElement.textContent = "replay";
    }
  };
}

async function startReplay() {
  if (!activeSymbol) {
    return;
  }
  modeElement.textContent = "replay";
  const now = Math.floor(Date.now() / 1000);
  const response = await fetch(adapterUrl("/replay/sessions"), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      symbols: [activeSymbol],
      from: now - 24 * 60 * 60,
      to: now,
      speed: 10,
      mode: "multiplier",
    }),
  });
  const session = await response.json();
  setMessage(`replay ${session.id} ${session.status}`);
}

function toChartBar(bar) {
  return {
    time: Math.floor(bar.time_ms / 1000),
    open: bar.open,
    high: bar.high,
    low: bar.low,
    close: bar.close,
  };
}

function toVolumeBar(bar) {
  return {
    time: Math.floor(bar.time_ms / 1000),
    value: bar.volume || 0,
    color: bar.close >= bar.open ? "#0f766e" : "#b42318",
  };
}

function adapter() {
  return adapterInput.value.replace(/\/$/, "");
}

function adapterUrl(path) {
  const base = adapter() || defaultAdapterUrl();
  return new URL(base.replace(/\/$/, "") + path, window.location.href).toString();
}

function websocketUrl(path) {
  const url = new URL(adapterUrl(path));
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function defaultAdapterUrl() {
  const host = window.location.hostname || "127.0.0.1";
  const protocol = window.location.protocol === "https:" ? "https:" : "http:";
  if (window.location.port !== "8091") {
    return `${window.location.origin}/api`;
  }
  return `${protocol}//${host}:8090`;
}

function micros(value) {
  return typeof value === "number" ? value / 1_000_000 : null;
}

function numberOrDash(value) {
  return typeof value === "number" && Number.isFinite(value) ? value.toFixed(4) : "-";
}

function setMessage(message, warn = false) {
  messageElement.textContent = message;
  messageElement.classList.toggle("warn", warn);
}

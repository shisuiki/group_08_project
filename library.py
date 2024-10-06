import base64
import requests
from datetime import datetime
import json
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.exceptions import InvalidSignature
import websockets
from typing import Dict, List


def load_private_key_from_file(path):
    with open(path, "rb") as file:
        return serialization.load_pem_private_key(file.read(), password=None, backend=default_backend())


def get_time_str():
    return str(int(datetime.now().timestamp() * 1000))


class OrderBook:
    def __init__(self, yes_list: list, no_list: list):
        self.bids = dict()
        self.asks = dict()
        for i in range(1, 100):
            self.bids[i] = 0
            self.asks[i] = 0
        for yes in yes_list:
            self.bids[yes[0]] = yes[1]
        for no in no_list:
            self.asks[100 - no[0]] = no[1]

    def get_bids(self):
        return self.bids

    def get_asks(self):
        return self.asks

    def update_book(self, side: str, price: int, delta: int):
        if side == "yes":
            self.bids[price] += delta
        elif side == "no":
            self.asks[100 - price] += delta
        self.print()

    def print(self):
        print("Bid Size | Price | Ask Size")
        print("---------------------------")
        for i in range(99, 0, -1):
            bid, ask = self.bids[i], self.asks[i]
            if bid == 0 and ask == 0:
                continue
            print(f"{bid:<9}| {i:<6}| {self.asks[i]}")


class KalshiWrapper:
    def __init__(self, key_id: str, key_path: str, use_demo=False):
        self.key_id = key_id
        self.private_key = load_private_key_from_file(key_path)
        self.base_url = "https://demo-api.kalshi.com" if use_demo else "https://trading-api.kalshi.com"

    def sign_msg(self, msg: str) -> str:
        message = msg.encode("utf-8")
        try:
            signature = self.private_key.sign(
                message,
                padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=padding.PSS.DIGEST_LENGTH),
                hashes.SHA256()
            )
            return base64.b64encode(signature).decode("utf-8")
        except InvalidSignature as e:
            raise ValueError("RSA sign PSS failed") from e

    def send_get(self, path: str, params: dict = None):
        timestamp_str = get_time_str()
        msg = timestamp_str + "GET" + path
        signature = self.sign_msg(msg)
        headers = {
            "KALSHI-ACCESS-KEY": self.key_id,
            "KALSHI-ACCESS-SIGNATURE": signature,
            "KALSHI-ACCESS-TIMESTAMP": timestamp_str,
            "accept": "application/json"
        }
        return requests.get(self.base_url + path, headers=headers, params=params)

    def get_balance(self):
        return self.send_get("/trade-api/v2/portfolio/balance")

    def get_fills(self, ticker: str = None, order_id: str = None, min_ts: int = None,
                  max_ts: int = None, limit: int = None, cursor: str = None):
        params = {}
        if ticker is not None:
            params['ticker'] = ticker
        if order_id is not None:
            params['order_id'] = order_id
        if min_ts is not None:
            params['min_ts'] = min_ts
        if max_ts is not None:
            params['max_ts'] = max_ts
        if limit is not None:
            params['limit'] = limit
        if cursor is not None:
            params['cursor'] = cursor
        return self.send_get("/trade-api/v2/portfolio/fills", params)

    def get_orders(self, ticker: str = None, event_ticker: str = None, min_ts: int = None, max_ts: int = None,
                   status: str = None, cursor: str = None, limit: int = None):
        params = {}
        if ticker is not None:
            params['ticker'] = ticker
        if event_ticker is not None:
            params['event_ticker'] = event_ticker
        if min_ts is not None:
            params['min_ts'] = min_ts
        if max_ts is not None:
            params['max_ts'] = max_ts
        if status is not None:
            params['status'] = status
        if cursor is not None:
            params['cursor'] = cursor
        if limit is not None:
            params['limit'] = limit
        return self.send_get("/trade-api/v2/portfolio/orders", params)

    def get_order(self, order_id: str):
        return self.send_get(f"/trade-api/v2/portfolio/orders/{order_id}")

    def get_positions(self, cursor: str = None, limit: int = None, count_filter: str = None,
                      settlement_status: str = None, ticker: str = None, event_ticker: str = None):
        params = {}
        if cursor is not None:
            params['cursor'] = cursor
        if limit is not None:
            params['limit'] = limit
        if count_filter is not None:
            params['count_filter'] = count_filter
        if settlement_status is not None:
            params['settlement_status'] = settlement_status
        if ticker is not None:
            params['ticker'] = ticker
        if event_ticker is not None:
            params['event_ticker'] = event_ticker

        return self.send_get("/trade-api/v2/portfolio/positions", params)

    def get_portfolio_settlements(self, limit: int = None, min_ts: int = None, max_ts: int = None, cursor: str = None):
        params = {}
        if limit is not None:
            params['limit'] = limit
        if min_ts is not None:
            params['min_ts'] = min_ts
        if max_ts is not None:
            params['max_ts'] = max_ts
        if cursor is not None:
            params['cursor'] = cursor
        return self.send_get("/trade-api/v2/portfolio/settlements", params)

    def get_portfolio_resting_order_total_value(self):
        return self.send_get("/trade-api/v2/portfolio/summary/total_resting_order_value")

    def get_exchange_schedule(self):
        return self.send_get("/trade-api/v2/exchange/schedule")

    def get_exchange_announcements(self):
        return self.send_get("/trade-api/v2/exchange/status")

    def get_events(self, limit: int = None, cursor: str = None, status: str = None,
                   series_ticker: str = None, with_nested_markets: bool = None):
        params = {}
        if limit is not None:
            params['limit'] = limit
        if cursor is not None:
            params['cursor'] = cursor
        if status is not None:
            params['status'] = status
        if series_ticker is not None:
            params['series_ticker'] = series_ticker
        if with_nested_markets is not None:
            params['with_nested_markets'] = with_nested_markets
        return self.send_get("/trade-api/v2/events", params)

    def get_event(self, event_ticker: str, with_nested_markets: bool = None):
        params = {}
        if with_nested_markets is not None:
            params['with_nested_markets'] = with_nested_markets
        return self.send_get(f"/trade-api/v2/events/{event_ticker}", params)

    def get_markets(self, limit: int = None, cursor: str = None, event_ticker: str = None,
                    series_ticker: str = None, max_close_ts: int = None, min_close_ts: int = None,
                    status: str = None, tickers: str = None):
        params = {}
        if limit is not None:
            params['limit'] = limit
        if cursor is not None:
            params['cursor'] = cursor
        if event_ticker is not None:
            params['event_ticker'] = event_ticker
        if series_ticker is not None:
            params['series_ticker'] = series_ticker
        if max_close_ts is not None:
            params['max_close_ts'] = max_close_ts
        if min_close_ts is not None:
            params['min_close_ts'] = min_close_ts
        if status is not None:
            params['status'] = status
        if tickers is not None:
            params['tickers'] = tickers
        return self.send_get("/trade-api/v2/markets", params)

    def get_trades(self, cursor: str = None, limit: int = None, ticker: str = None,
                   min_ts: int = None, max_ts: int = None):
        params = {}
        if cursor is not None:
            params['cursor'] = cursor
        if limit is not None:
            params['limit'] = limit
        if ticker is not None:
            params['ticker'] = ticker
        if min_ts is not None:
            params['min_ts'] = min_ts
        if max_ts is not None:
            params['max_ts'] = max_ts
        return self.send_get("/trade-api/v2/markets/trades", params)

    def get_market(self, ticker: str):
        return self.send_get(f"/trade-api/v2/markets/{ticker}")

    def get_market_orderbook(self, ticker: str, depth: int = None):
        params = {}
        if depth is not None:
            params['depth'] = depth
        return self.send_get(f"/trade-api/v2/markets/{ticker}/orderbook", params)

    def get_series(self, series_ticker: str):
        return self.send_get(f"/trade-api/v2/series/{series_ticker}")

    def get_market_candlesticks(self, series_ticker: str, ticker: str, start_ts: int, end_ts: int,
                                period_interval: int):
        params = {
            "start_ts": start_ts,
            "end_ts": end_ts,
            "period_interval": period_interval
        }
        return self.send_get(f"/trade-api/v2/series/{series_ticker}/markets/{ticker}/candlesticks", params)


class Market:
    def __init__(self, market_obj: dict):
        self.ticker = market_obj["ticker"]
        self.event_ticker = market_obj["event_ticker"]
        self.strike_type = market_obj["strike_type"]
        self.floor_strike = market_obj.get("floor_strike")
        self.cap_strike = market_obj.get("cap_strike")
        self.close_time = datetime.strptime(market_obj["close_time"], "%Y-%m-%dT%H:%M:%SZ")
        self.subtitle = market_obj["subtitle"]

    def get_lowest_strike(self):
        if self.floor_strike is None:
            return self.cap_strike
        if self.cap_strike is None:
            return self.floor_strike
        return min(self.floor_strike, self.cap_strike)


def list_markets(wrapper: KalshiWrapper, series_ticker: str):
    markets_resp = wrapper.get_markets(series_ticker=series_ticker)
    markets = json.loads(markets_resp.text)["markets"]
    event_directory = dict()  # event_ticker: list[Market]
    curr_time = datetime.utcnow()
    for market_obj in markets:
        close_time = datetime.strptime(market_obj["close_time"], "%Y-%m-%dT%H:%M:%SZ")
        if curr_time < close_time:
            market = Market(market_obj)
            if market.event_ticker not in event_directory:
                event_directory[market.event_ticker] = list()
            event_directory[market.event_ticker].append(market)

    event_tickers = list(sorted(event_directory.keys()))
    for event_ticker in event_tickers:
        event_directory[event_ticker].sort(key=lambda market: market.get_lowest_strike())
        for market in event_directory[event_ticker]:
            print(f"MARKET {market.ticker} ({market.subtitle})")


class KalshiListener:
    def __init__(self, msg_type: str):
        self.msg_type = msg_type

    def receive(self, data):
        if "type" in data and data["type"] == self.msg_type:
            self.process(data)

    def process(self, data):
        pass


class OrderbookSnapshotListener(KalshiListener):
    def __init__(self, instance):
        self.instance = instance
        super().__init__("orderbook_snapshot")

    def process(self, data):
        print("Received orderbook snapshot message!")
        if "msg" in data:
            msg = data["msg"]
            if "market_ticker" in msg:
                market_ticker = msg["market_ticker"]
                self.instance.get_orderbooks()[market_ticker] = OrderBook(msg["yes"], msg["no"])
                self.instance.get_orderbooks().get(market_ticker).print()


class OrderbookDeltaListener(KalshiListener):
    def __init__(self, instance):
        self.instance = instance
        super().__init__("orderbook_delta")

    def process(self, data):
        print("Received orderbook delta message!")
        if "msg" in data:
            msg = data["msg"]
            if "market_ticker" in msg:
                market_ticker = msg["market_ticker"]
                orderbook = self.instance.get_orderbooks().get(market_ticker)
                if orderbook is not None:
                    orderbook.update_book(msg["side"], msg["price"], msg["delta"])
                    orderbook.print()


class KalshiWSClient:
    def __init__(self, wrapper: KalshiWrapper):
        endpoint = "/trade-api/ws/v2"
        self.uri = wrapper.base_url.replace("https://", "wss://") + endpoint
        timestamp_str = get_time_str()
        msg = timestamp_str + "GET" + endpoint
        signature = wrapper.sign_msg(msg)
        self.headers = {
            "KALSHI-ACCESS-KEY": wrapper.key_id,
            "KALSHI-ACCESS-SIGNATURE": signature,
            "KALSHI-ACCESS-TIMESTAMP": timestamp_str,
            "accept": "application/json"
        }
        self.websocket = None
        self.listeners: List[KalshiListener] = []
        self.nonce = 1
        self.sids = set()
        self.listening = False

    async def connect(self):
        print("Connecting...")
        self.websocket = await websockets.connect(self.uri, extra_headers=self.headers)
        self.listening = True
        print(f"Connected to {self.uri}")

    async def close(self):
        if self.websocket:
            print("Closing...")
            self.listening = False
            await self.websocket.close()
            self.websocket = None
            print("Closed websocket connection")

    async def listen(self):
        print("Listening...")
        while self.listening:
            try:
                print("............")
                message = await self.websocket.recv()
                data = json.loads(message)
                print("Received message:")
                print(json.dumps(data, indent=4))
                if "type" in data and data["type"] == "subscribed":
                    self.sids.add(data["msg"]["sid"])
                for listener in self.listeners:
                    listener.receive(data)
            except websockets.ConnectionClosed:
                print("Connection closed")
                self.listening = False

    async def send_message(self, message: dict):
        if self.websocket:
            await self.websocket.send(json.dumps(message))
            print(f"Sent message: {message}")
        else:
            print("WebSocket connection is not established yet.")

    async def subscribe(self, channels: List[str], market_ticker: str = None, market_tickers: List[str] = None):
        params = {
            "channels": channels
        }
        if market_tickers is not None:
            params["market_tickers"] = market_tickers
        elif market_ticker is not None:
            params["market_ticker"] = market_ticker
        message = {
            "id": self.get_nonce(),
            "cmd": "subscribe",
            "params": params
        }
        await self.send_message(message)

    async def unsubscribe(self, sids: List[int]):
        print(f"Unsubscribing from {sids}")
        message = {
            "id": self.get_nonce(),
            "cmd": "unsubscribe",
            "params": {
                "sids": sids
            }
        }
        await self.send_message(message)

    async def update_subscription(self, sid: int, action: str, market_ticker: str = None,
                                  market_tickers: List[str] = None):
        if action not in ["add_markets", "delete_markets"]:
            print("Action must be add_markets or delete_markets")
            return
        params = {
            "sids": [sid],
            "action": action
        }
        if market_tickers is not None:
            params["market_tickers"] = market_tickers
        elif market_ticker is not None:
            params["market_ticker"] = market_ticker
        message = {
            "id": self.get_nonce(),
            "cmd": "update_subscription",
            "params": params
        }
        await self.send_message(message)

    def add_listener(self, listener: KalshiListener):
        self.listeners.append(listener)

    def get_nonce(self):
        current_nonce = self.nonce
        self.nonce += 1
        return current_nonce

    def get_sids(self):
        return list(self.sids)


class KalshiInstance:
    def __init__(self):
        self.orderbooks: Dict[str, OrderBook] = dict()

        self.wrapper = KalshiWrapper("01208ff2-c1e7-4ef2-86c1-5a6c7ec32720", "general.key")
        self.ws_client = KalshiWSClient(self.wrapper)
        self.ws_client.add_listener(OrderbookSnapshotListener(self))
        self.ws_client.add_listener(OrderbookDeltaListener(self))

    async def run_listener(self):
        await self.ws_client.connect()
        await self.ws_client.subscribe(channels=["orderbook_delta"], market_ticker="RATECUTCOUNT-24DEC31-T4")
        try:
            await self.ws_client.listen()
        except KeyboardInterrupt:
            await self.stop_listener()

    async def stop_listener(self):
        await self.ws_client.unsubscribe(self.ws_client.get_sids())
        await self.ws_client.close()

    def get_orderbooks(self) -> Dict[str, OrderBook]:
        return self.orderbooks

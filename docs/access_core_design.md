# 接入层 & 核心层重构方案

## 接入层（L1）
- **AccessLayerCoordinator**：集中管理多个 `MarketDataConnector`，统一启动/停用。
- **MarketDataConnector**：抽象接口，屏蔽不同交易所的网络细节。
  - `BinanceMarketDataConnector`：订阅参考行情（aggTrade + depth），供策略定价。
  - `TargetExchangeMarketDataConnector`：占位，负责目标交易所（做市侧）行情。
- **Normalizer**：从各个连接器接收 `Exchange` + JSON，转换为复用的 `CoreEvent`，并在 `event.exchangeId` 中标明来源。
- **SessionManager/ListenKeyRefresher**：对目标交易所复用，供后续交易端建立私有流。

该设计允许在不改动核心逻辑的情况下按需增加新的交易所连接器（例如 OKX、Bybit），同时策略层可以同时观测多家参考盘口。

## 核心层（L2）
- **CoreEvent**：新增 `exchangeId` 字段，所有行情/成交事件都能标记来源。
- **LobManager + OrderBookRegistry**：
  - `OrderBookRegistry` 维护 `EnumMap<Exchange, OrderBookSnapshot>`，拆分参考盘与目标盘。
  - `LobManager` 根据 `exchange.referenceOnly()` 自动路由到参考/目标盘口，并提供 `primaryReference()`（默认 Binance）。
  - 后续会在此实现「快照 + 增量」逻辑，引用 `DepthUpdate` 占位结构。
- **StrategyEngine**：仍以 `primaryReference()` 为默认输入，后续可扩展成读取多个参考盘做聚合价。

## 数据流
1. `MarketDataConnector` → `Normalizer`（含 `Exchange`）→ RingBuffer `CoreEvent`。
2. `CoreEventHandler` 根据 `exchangeId` 调用 `LobManager`，分别更新参考盘/目标盘。
3. 策略层获取参考盘口（或未来的合成盘口），结合目标盘口状态做套利/做市决策。

## 后续待办
- 为 `TargetExchangeMarketDataConnector` 接上实际行情/快照接口。
- `Normalizer` 完成 Binance/目标所 JSON 解析，并输出 `DepthUpdate`/`TradeUpdate`。
- `LobManager` 完善深度维护逻辑（参见 `docs/binance_local_orderbook.md`）。


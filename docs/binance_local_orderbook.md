# Binance 本地订单簿维护步骤

> 摘自官方文档，结合本项目实践场景的要点整理。

1. **建立深度订阅**
   - 通过 `wss://stream.binance.com:9443/ws/<symbol>@depth` 打开 WebSocket（示例：`bnbbtc@depth`）。
   - 缓存收到的每条事件，并记录第一条事件的 `U`。

2. **获取 REST 快照**
   - 调用 `https://api.binance.com/api/v3/depth?symbol=<SYMBOL>&limit=5000`。
   - 如果快照里的 `lastUpdateId < U`，说明快照过旧，重新请求。

3. **对齐缓冲区**
   - 丢弃所有 `u <= lastUpdateId` 的事件。
   - 确保缓冲区第一条事件的 `[U, u]` 覆盖 `lastUpdateId`，否则重新开始。

4. **初始化本地订单簿**
   - 将快照写入本地结构，同时把本地 `updateId` 设置为 `lastUpdateId`。
   - 对缓冲区事件按顺序执行“增量更新”流程，然后继续消费实时事件。

5. **增量更新规则**
   - 若事件 `u < 本地 updateId`，忽略。
   - 若事件 `U > 本地 updateId + 1`，说明丢包，丢弃本地订单簿重新开始。
   - 正常情况下，下一条事件的 `U == 前一条事件的 u + 1`。
   - 遍历 `bids`/`asks`：
     - 价格档不存在则插入。
     - 数量为 0 则删除该档。
   - 将本地 `updateId` 设置为事件的 `u`。

6. **注意事项**
   - REST 快照最多 5000 个价位（一侧），快照外的档位只有变动才会出现在后续事件中。
   - 对超出快照深度的价格档要谨慎使用。


# 策略管理接口文档

## 基础信息

- **基础路径**: `/api/strategy`
- **认证方式**: 启动策略接口需要在请求头中携带 `Authorization: Bearer {token}`

---

## 1. 启动刷量策略

**接口**: `POST /api/strategy/start`

### 请求参数

**请求头**:
- `Authorization`: Bearer {token} (必填)

**请求体** (所有参数均为可选，未提供时使用默认值):
| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| buyAccountIds | Integer[] | 是 | [1,2,3,4,5] | 买单账户ID数组 |
| sellAccountIds | Integer[] | 是 | [6,7,8,9,10] | 卖单账户ID数组 |
| minPrice | Double | 否 | 30.0 | 最小价格（接口自动转换为E8精度） |
| maxPrice | Double | 否 | 45.0 | 最大价格（接口自动转换为E8精度） |
| tickSize | Double | 否 | 0.01 | 价格步长（接口自动转换为E8精度） |
| volatilityPercent | Double | 否 | 3.0 | 波动率百分比 |
| symbolId | Short | 否 | 5 | 交易对ID |
| baseAssetId | String | 否 | "H2" | 基础资产ID |
| exchangeId | Short | 否 | 1 | 交易所ID（1=dYdX） |
| cycleDuration | Long | 否 | 10800 | 周期时长（秒，默认3小时，接口自动转换为毫秒） |
| targetVolume | Double | 否 | 500.0 | 目标交易量（接口自动转换为E8精度） |
| triggerInterval | Long | 否 | 3 | 触发间隔（秒，接口自动转换为毫秒） |
| enableVolumeTarget | Boolean | 否 | true | 是否启用交易量目标 |
| makerCounts | Integer | 否 | 6 | 做市单数量 |
| noiseFactory | Double | 否 | 0.5 | 噪声因子 |
| minInterval | Long | 否 | 3 | 最小下单间隔（秒，可选，接口自动转换为毫秒） |
| maxInterval | Long | 否 | 6 | 最大下单间隔（秒，可选，接口自动转换为毫秒） |
| progressThreshold | Double | 否 | 0.7 | 上涨/下跌周期分界点（0.0 ~ 1.0），小于此值为上涨周期，大于等于此值为下跌周期 |
| minCorrectionIntervalRatio | Double | 否 | 0.015 | 最小回调间隔：周期时长的百分比（如 0.015 表示 1.5%） |
| maxCorrectionIntervalRatio | Double | 否 | 0.08 | 最大回调间隔：周期时长的百分比（如 0.08 表示 8%） |
| minCorrectionAmplitudePercent | Double | 否 | 2.0 | 最小回调幅度：百分比（如 2.0 表示 2%） |
| maxCorrectionAmplitudePercent | Double | 否 | 6.0 | 最大回调幅度：百分比（如 6.0 表示 6%） |
| minCorrectionDurationRatio | Double | 否 | 0.017 | 最小回调持续时间：周期时长的百分比（如 0.017 表示 1.7%） |
| maxCorrectionDurationRatio | Double | 否 | 0.04 | 最大回调持续时间：周期时长的百分比（如 0.04 表示 4%） |
| convergenceThresholdPercent | Double | 否 | 5.0 | 收敛阈值：百分比（如 5.0 表示 5%），如果进度落后超过此值，策略会加速 |

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "策略启动成功（symbolId=5）: minPrice=30.00, maxPrice=45.00, volatility=3.00%",
  "success": true,
  "symbolId": 5
}
```

**失败响应**:
- `401`: 未授权，请先登录
- `500`: 启动策略失败（策略已在运行中或启动失败）

---

## 2. 停止策略

**接口**: `POST /api/strategy/stop`

### 请求参数

**请求体**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| symbolId | Short | 否* | 交易对ID（stopAll=false时必填） |
| stopAll | Boolean | 否 | 是否停止所有策略，默认false |

**说明**: 当 `stopAll=true` 时，停止所有策略；当 `stopAll=false` 时，必须提供 `symbolId`。

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "策略已停止（symbolId=5）",
  "success": true
}
```

或停止所有策略:
```json
{
  "code": 200,
  "message": "所有策略已停止",
  "success": true
}
```

**失败响应**:
- `400`: 请指定 symbolId 或设置 stopAll=true
- `500`: 停止策略失败

---

## 3. 获取策略状态（刷新策略运行状况）

**接口**: `GET /api/strategy/status`

**说明**: 此接口用于实时刷新策略的运行状况，可以查询单个策略或所有策略的状态。适用于前端定时轮询刷新策略运行状态。

### 请求参数

**查询参数**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| symbolId | Short | 否 | 交易对ID，不提供时返回所有策略状态 |

### 响应参数

**指定 symbolId 的成功响应 (code: 200)**:
```json
{
  "code": 200,
  "success": true,
  "data": {
    "isRunning": true,
    "symbolId": 5,
    "strategyType": "InternalRangeOscillatorStrategy2",
    "config": {
      "buyAccountIds": [1, 2, 3, 4, 5],
      "sellAccountIds": [6, 7, 8, 9, 10],
      "minPriceE8": 3000000000,
      "maxPriceE8": 4500000000,
      "tickSizeE8": 1000000,
      "volatilityPercent": 3.0,
      "symbolId": 5,
      "baseAssetId": "H2",
      "quoteAssetId": "USDT",
      "exchangeId": 1,
      "cycleDurationMs": 10800000,
      "targetVolumeE8": 50000000000,
      "triggerIntervalMs": 3000,
      "enableVolumeTarget": true,
      "makerCounts": 6,
      "noiseFactory": 0.5,
      "minIntervalMs": 3000,
      "maxIntervalMs": 6000
    },
    "runtimeStatus": {
      "accumulatedVolumeE8": 25000000000,
      "cycleStartTime": 1699000000000,
      "currentTargetPriceE8": 3750000000.0,
      "isRising": true,
      "initialized": true,
      "inCorrection": false,
      "minPriceE8": 3000000000,
      "maxPriceE8": 4500000000,
      "targetVolumeE8": 50000000000,
      "cycleDurationMs": 10800000,
      "volatilityPercent": 3.0,
      "enableVolumeTarget": true,
      "makerCounts": 6,
      "noiseFactor": 0.5,
      "minOrderQtyE8": 1000000000,
      "maxOrderQtyE8": 5000000000
    }
  }
}
```

**策略未运行时的响应**:
```json
{
  "code": 200,
  "success": true,
  "data": {
    "isRunning": false,
    "symbolId": 5,
    "strategyType": null,
    "config": null,
    "runtimeStatus": null
  }
}
```

**所有策略状态的成功响应 (code: 200)**:
```json
{
  "code": 200,
  "success": true,
  "data": {
    "5": {
      "isRunning": true,
      "symbolId": 5,
      "strategyType": "InternalRangeOscillatorStrategy2",
      "config": { ... },
      "runtimeStatus": { ... }
    },
    "6": {
      "isRunning": false,
      "symbolId": 6,
      "strategyType": null,
      "config": null,
      "runtimeStatus": null
    }
  }
}
```

**响应字段说明**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 响应状态码，200 表示成功 |
| success | Boolean | 请求是否成功 |
| data | Object/Map | 策略状态对象（单个查询）或 Map<symbolId, StrategyStatus>（查询所有） |

**StrategyStatus 对象字段**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| isRunning | Boolean | 策略是否正在运行 |
| symbolId | Short | 交易对ID |
| strategyType | String | 策略类型（如 "InternalRangeOscillatorStrategy2"），未运行时为 null |
| config | Object | 策略配置参数（StrategyConfig 对象），未运行时为 null |
| runtimeStatus | Object | 策略运行时状态（StrategyStatusInfo 对象），仅 InternalRangeOscillatorStrategy2 支持，未运行或其他策略类型时为 null |

**StrategyStatusInfo 对象字段**（runtimeStatus，仅 InternalRangeOscillatorStrategy2 策略返回）:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| accumulatedVolumeE8 | Long | 累计成交量（E8精度，需除以 1e8 得到实际数量） |
| cycleStartTime | Long | 周期开始时间（毫秒时间戳） |
| currentTargetPriceE8 | Double | 当前目标价格（E8精度，需除以 1e8 得到实际价格） |
| isRising | Boolean | 价格是否处于上涨趋势 |
| initialized | Boolean | 策略是否已完成初始化 |
| inCorrection | Boolean | 是否处于价格回调中 |
| minPriceE8 | Long | 最低价（E8精度） |
| maxPriceE8 | Long | 最高价（E8精度） |
| targetVolumeE8 | Long | 目标交易量（E8精度） |
| cycleDurationMs | Long | 周期时长（毫秒） |
| volatilityPercent | Double | 波动率百分比 |
| enableVolumeTarget | Boolean | 是否启用目标量控制 |
| makerCounts | Integer | Maker订单数量 |
| noiseFactor | Double | 噪声因子 |
| minOrderQtyE8 | Long | 最小订单数量（E8精度） |
| maxOrderQtyE8 | Long | 最大订单数量（E8精度） |

**失败响应**:
- `500`: 获取策略状态失败

**使用场景**:
- 前端定时轮询刷新策略运行状态（建议每 1-5 秒调用一次）
- 实时监控策略运行情况，包括：
  - 策略是否正在运行
  - 当前目标价格和价格趋势
  - 累计成交量及完成进度
  - 周期进度和时间信息
- 判断策略是否需要手动干预

**注意事项**:
- 此接口不需要认证，可公开访问
- 查询单个策略时，如果策略未运行，返回 `isRunning: false`，`config` 和 `runtimeStatus` 为 null
- 查询所有策略时，返回所有已注册的 symbolId 的状态（包括未运行的）
- `runtimeStatus` 字段仅在策略类型为 `InternalRangeOscillatorStrategy2` 时返回，其他策略类型为 null
- 价格和数量字段使用 E8 精度（乘以 1e8），前端需要除以 1e8 得到实际数值
- 时间字段使用毫秒时间戳，前端需要转换为可读格式

---

## 4. 获取交易员的策略列表

**接口**: `GET /api/strategy/strategies`

### 请求参数

**请求头**:
- `Authorization`: Bearer {token} (必填)

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "success": true,
  "data": [
    {
      "isRunning": true,
      "symbolId": 5,
      "strategyType": "InternalRangeOscillatorStrategy2",
      "config": {
        "buyAccountIds": [1, 2, 3, 4, 5],
        "sellAccountIds": [6, 7, 8, 9, 10],
        "minPriceE8": 3000000000,
        "maxPriceE8": 4500000000,
        "tickSizeE8": 1000000,
        "volatilityPercent": 3.0,
        "symbolId": 5,
        "baseAssetId": "H2",
        "quoteAssetId": "USDT",
        "exchangeId": 1,
        "cycleDurationMs": 10800000,
        "targetVolumeE8": 50000000000,
        "triggerIntervalMs": 3000,
        "enableVolumeTarget": true,
        "makerCounts": 6,
        "noiseFactory": 0.5,
        "minIntervalMs": 3000,
        "maxIntervalMs": 6000
      },
      "runtimeStatus": {
        "currentPrice": 37.5,
        "cycleProgress": 0.3,
        "volumeProgress": 0.45,
        "lastOrderTime": 1699000000000
      }
    }
  ],
  "count": 1
}
```

**响应字段说明**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 响应状态码，200 表示成功 |
| success | Boolean | 请求是否成功 |
| data | Array | 策略状态列表 |
| count | Integer | 策略数量 |

**StrategyStatus 对象字段**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| isRunning | Boolean | 策略是否正在运行 |
| symbolId | Short | 交易对ID |
| strategyType | String | 策略类型（如 "InternalRangeOscillatorStrategy2"） |
| config | Object | 策略配置参数（StrategyConfig 对象） |
| runtimeStatus | Object | 策略运行时状态（仅 InternalRangeOscillatorStrategy2 支持，可能为 null） |

**StrategyConfig 对象字段**:

| 字段名 | 类型 | 说明 |
|--------|------|------|
| buyAccountIds | Short[] | 买单账户ID数组 |
| sellAccountIds | Short[] | 卖单账户ID数组 |
| minPriceE8 | Long | 最小价格（E8精度，需除以 1e8 得到实际价格） |
| maxPriceE8 | Long | 最大价格（E8精度，需除以 1e8 得到实际价格） |
| tickSizeE8 | Long | 价格步长（E8精度） |
| volatilityPercent | Double | 波动率百分比 |
| symbolId | Short | 交易对ID |
| baseAssetId | String | 基础资产ID（如 "H2"） |
| quoteAssetId | String | 计价资产ID（固定为 "USDT"） |
| exchangeId | Short | 交易所ID（1=dYdX） |
| cycleDurationMs | Long | 周期时长（毫秒） |
| targetVolumeE8 | Long | 目标交易量（E8精度） |
| triggerIntervalMs | Long | 触发间隔（毫秒） |
| enableVolumeTarget | Boolean | 是否启用交易量目标 |
| makerCounts | Integer | 做市单数量 |
| noiseFactory | Double | 噪声因子 |
| minIntervalMs | Long | 最小下单间隔（毫秒，可选） |
| maxIntervalMs | Long | 最大下单间隔（毫秒，可选） |
| progressThreshold | Double | 上涨/下跌周期分界点（0.0 ~ 1.0），可选 |
| minCorrectionIntervalRatio | Double | 最小回调间隔：周期时长的百分比，可选 |
| maxCorrectionIntervalRatio | Double | 最大回调间隔：周期时长的百分比，可选 |
| minCorrectionAmplitudePercent | Double | 最小回调幅度：百分比，可选 |
| maxCorrectionAmplitudePercent | Double | 最大回调幅度：百分比，可选 |
| minCorrectionDurationRatio | Double | 最小回调持续时间：周期时长的百分比，可选 |
| maxCorrectionDurationRatio | Double | 最大回调持续时间：周期时长的百分比，可选 |
| convergenceThresholdPercent | Double | 收敛阈值：百分比，可选 |

**失败响应**:
- `401`: 未授权，请先登录 或 用户不存在
- `500`: 获取策略列表失败

**说明**:
- 此接口返回当前登录交易员的所有正在运行的策略
- 如果交易员没有运行中的策略，返回空数组 `[]`，count 为 0
- `runtimeStatus` 字段仅在策略类型为 `InternalRangeOscillatorStrategy2` 时返回，其他策略类型为 `null`
- 价格和数量字段使用 E8 精度（乘以 1e8），前端需要除以 1e8 得到实际数值
- 时间字段使用毫秒，前端需要除以 1000 得到秒数

---

## 使用流程

1. **登录获取 token**: 先调用认证接口 `/api/auth/login` 获取 token
2. **配置账号**: 使用账号管理接口添加和配置交易账号
3. **启动策略**: 使用 `POST /api/strategy/start` 启动策略，系统会自动：
   - 初始化当前交易员的所有账号（TradeSession 和资产余额）
   - 订阅指定账户的订单更新
   - 创建并启动策略实例
4. **查询状态**: 使用 `GET /api/strategy/status` 查询策略运行状态
5. **停止策略**: 使用 `POST /api/strategy/stop` 停止指定或所有策略

**注意事项**:
- 启动策略时会自动初始化当前交易员的所有账号，确保账号已配置
- 同一 symbolId 只能运行一个策略，启动前需先停止已运行的策略
- **参数精度说明**：
  - 价格参数（minPrice、maxPrice、tickSize）和数量参数（targetVolume）：前端传普通数值（如 30.0），接口自动转换为 E8 精度（乘以 1e8）
  - 时间参数（cycleDuration、triggerInterval、minInterval、maxInterval）：前端传秒数（如 3），接口自动转换为毫秒（乘以 1000）
- 策略按 symbolId 路由，不同 symbolId 可以同时运行多个策略
- **回调配置参数说明**：
  - `progressThreshold`：控制价格上涨和下跌周期的分界点。例如设置为 0.7 表示：周期前 70% 的时间为上涨周期，后 30% 的时间为下跌周期
  - `minCorrectionIntervalRatio` / `maxCorrectionIntervalRatio`：控制回调之间的时间间隔。例如设置为 0.015 和 0.08，表示回调间隔在周期时长的 1.5% ~ 8% 之间随机
  - `minCorrectionAmplitudePercent` / `maxCorrectionAmplitudePercent`：控制回调的幅度。例如设置为 2.0 和 6.0，表示回调幅度在 2% ~ 6% 之间随机
  - `minCorrectionDurationRatio` / `maxCorrectionDurationRatio`：控制回调的持续时间。例如设置为 0.017 和 0.04，表示回调持续时间在周期时长的 1.7% ~ 4% 之间随机
  - `convergenceThresholdPercent`：收敛阈值，用于判断策略是否需要加速。如果实际价格与目标价格的差距超过此阈值，策略会加速调整价格


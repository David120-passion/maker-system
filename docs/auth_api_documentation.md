# 认证接口文档

## 基础信息

- **基础路径**: `/api/auth`
- **认证方式**: Token 认证（登录后获取 token，后续请求在 Header 中携带 `Authorization: Bearer {token}`）

---

## 1. 用户登录

### 接口信息
- **路径**: `/api/auth/login`
- **方法**: `POST`
- **说明**: 用户登录，获取访问 token

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名（默认: `trader1`） |
| password | String | 是 | 密码（默认: `trader1`） |

### 请求示例

```json
{
  "username": "trader1",
  "password": "trader1"
}
```

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示成功 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示成功 |
| data | Object | 响应数据对象 |
| data.token | String | 访问令牌，用于后续接口认证 |
| data.username | String | 用户名 |
| data.expiresIn | Integer | token 有效期（秒），默认 3600 秒（1小时） |

#### 成功响应示例

```json
{
  "code": 200,
  "message": "登录成功",
  "success": true,
  "data": {
    "token": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
    "username": "trader1",
    "expiresIn": 3600
  }
}
```

#### 失败响应

**参数为空 (code: 400)**
```json
{
  "code": 400,
  "message": "用户名和密码不能为空",
  "success": false
}
```

**用户名或密码错误 (code: 401)**
```json
{
  "code": 401,
  "message": "用户名或密码错误",
  "success": false
}
```

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "登录失败: {错误详情}",
  "success": false
}
```

---

## 2. 验证 Token

### 接口信息
- **路径**: `/api/auth/verify`
- **方法**: `POST`
- **说明**: 验证 token 是否有效

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| token | String | 是 | 需要验证的访问令牌 |

### 请求示例

```json
{
  "token": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示 token 有效 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示 token 有效 |

#### 成功响应示例

```json
{
  "code": 200,
  "message": "token 有效",
  "success": true
}
```

#### 失败响应

**参数为空 (code: 400)**
```json
{
  "code": 400,
  "message": "token 不能为空",
  "success": false
}
```

**Token 无效或已过期 (code: 401)**
```json
{
  "code": 401,
  "message": "token 无效或已过期",
  "success": false
}
```

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "验证失败: {错误详情}",
  "success": false
}
```

---

## 3. 用户登出

### 接口信息
- **路径**: `/api/auth/logout`
- **方法**: `POST`
- **说明**: 用户登出，使 token 失效

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| token | String | 否 | 需要失效的访问令牌（如果为空，也会返回成功） |

### 请求示例

```json
{
  "token": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示成功 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示成功 |

#### 成功响应示例

```json
{
  "code": 200,
  "message": "登出成功",
  "success": true
}
```

#### 失败响应

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "登出失败: {错误详情}",
  "success": false
}
```

---

## 4. 创建交易员账号（仅超级管理员）

### 接口信息
- **路径**: `/api/auth/create-user`
- **方法**: `POST`
- **说明**: 创建新的交易员账号或管理员账号，只有超级管理员可以调用此接口
- **权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名（必须唯一） |
| password | String | 是 | 密码 |
| isAdmin | Boolean | 否 | 是否为超级管理员，默认 false（普通交易员） |

### 请求示例

```json
{
  "username": "trader2",
  "password": "password123",
  "isAdmin": false
}
```

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示成功 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示成功 |
| data | Object | 响应数据对象 |
| data.id | Integer | 用户ID（自增主键） |
| data.username | String | 用户名 |
| data.isAdmin | Boolean | 是否为超级管理员 |

#### 成功响应示例

```json
{
  "code": 200,
  "message": "用户创建成功",
  "success": true,
  "data": {
    "id": 2,
    "username": "trader2",
    "isAdmin": false
  }
}
```

#### 失败响应

**参数为空 (code: 400)**
```json
{
  "code": 400,
  "message": "用户名和密码不能为空",
  "success": false
}
```

**用户名已存在 (code: 400)**
```json
{
  "code": 400,
  "message": "用户名已存在: trader2",
  "success": false
}
```

**未授权 (code: 401)**
```json
{
  "code": 401,
  "message": "未授权，请先登录",
  "success": false
}
```

**权限不足 (code: 403)**
```json
{
  "code": 403,
  "message": "权限不足，只有超级管理员可以创建用户",
  "success": false
}
```

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "创建用户失败: {错误详情}",
  "success": false
}
```

---

## 5. 获取所有交易员列表（仅超级管理员）

### 接口信息
- **路径**: `/api/auth/users`
- **方法**: `GET`
- **说明**: 获取系统中所有交易员和管理员的列表，只有超级管理员可以调用此接口
- **权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**路径参数**: 无

**查询参数**: 无

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示成功 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示成功 |
| count | Integer | 用户总数 |
| data | Array | 用户列表 |
| data[].id | Integer | 用户ID（自增主键） |
| data[].username | String | 用户名 |
| data[].isAdmin | Boolean | 是否为超级管理员 |

#### 成功响应示例

```json
{
  "code": 200,
  "success": true,
  "count": 2,
  "data": [
    {
      "id": 1,
      "username": "trader1",
      "isAdmin": true
    },
    {
      "id": 2,
      "username": "trader2",
      "isAdmin": false
    }
  ]
}
```

#### 失败响应

**未授权 (code: 401)**
```json
{
  "code": 401,
  "message": "未授权，请先登录",
  "success": false
}
```

**权限不足 (code: 403)**
```json
{
  "code": 403,
  "message": "权限不足，只有超级管理员可以查看所有用户",
  "success": false
}
```

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "获取用户列表失败: {错误详情}",
  "success": false
}
```

---

## 6. 批量查询账户余额

### 接口信息
- **路径**: `/api/accounts/batch-balances`
- **方法**: `POST`
- **说明**: 批量查询多个 dYdX 账户地址的余额信息，使用多线程并行查询以提高性能
- **权限**: 需要登录认证

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（登录后获取的 token）

**请求体**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| addresses | Array<String> | 是 | dYdX 账户地址数组，支持批量查询多个地址 |

**注意**: `subaccountNumber` 固定为 1，无需在请求中指定。

### 请求示例

```json
{
  "addresses": [
    "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt",
    "0x1234567890abcdef1234567890abcdef12345678",
    "0xabcdef1234567890abcdef1234567890abcdef12"
  ]
}
```

### 响应参数

#### 成功响应 (code: 200)

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200 表示成功 |
| message | String | 响应消息 |
| success | Boolean | 是否成功，true 表示成功 |
| data | Object | 余额数据对象，key 为地址，value 为该地址的资产信息数组 |
| data.{address} | Array | 该地址的资产信息数组 |
| data.{address}[].symbol | String | 资产符号（如 "USDT", "BTC"） |
| data.{address}[].size | String | 资产数量（字符串格式） |

#### 成功响应示例

```json
{
  "code": 200,
  "message": "批量查询余额成功",
  "success": true,
  "data": {
    "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt": [
      {
        "symbol": "USDT",
        "size": "10000.5"
      },
      {
        "symbol": "BTC",
        "size": "0.5"
      }
    ],
    "0x1234567890abcdef1234567890abcdef12345678": [
      {
        "symbol": "USDT",
        "size": "5000.0"
      }
    ],
    "0xabcdef1234567890abcdef1234567890abcdef12": []
  }
}
```

**说明**:
- 如果某个地址查询失败，该地址对应的数组为空数组 `[]`
- 单个地址查询失败不会影响其他地址的查询结果
- 所有查询使用多线程并行执行，提高查询效率

#### 失败响应

**参数为空 (code: 400)**
```json
{
  "code": 400,
  "message": "addresses 不能为空",
  "success": false
}
```

**未授权 (code: 401)**
```json
{
  "code": 401,
  "message": "未授权，请先登录",
  "success": false
}
```

**服务器错误 (code: 500)**
```json
{
  "code": 500,
  "message": "批量查询余额失败: {错误详情}",
  "success": false
}
```

### 性能说明

- **并行查询**: 使用多线程池（10个线程）并行查询多个地址，大幅提升查询速度
- **容错处理**: 单个地址查询失败不影响其他地址，失败地址返回空数组
- **线程池复用**: 使用应用级别的共享线程池，避免每次请求创建新线程池

### 使用示例

**cURL 示例**:
```bash
curl -X POST http://localhost:8080/api/accounts/batch-balances \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6" \
  -d '{
    "addresses": [
      "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt",
      "0x1234567890abcdef1234567890abcdef12345678"
    ]
  }'
```

**JavaScript 示例**:
```javascript
const response = await fetch('http://localhost:8080/api/accounts/batch-balances', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6'
  },
  body: JSON.stringify({
    addresses: [
      'h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt',
      '0x1234567890abcdef1234567890abcdef12345678'
    ]
  })
});

const result = await response.json();
console.log(result.data);
```

---

## 状态码说明

| 状态码 | 说明 |
|--------|------|
| 200 | 请求成功 |
| 400 | 请求参数错误（参数为空或格式错误） |
| 401 | 认证失败（用户名密码错误或 token 无效） |
| 403 | 权限不足（需要超级管理员权限） |
| 500 | 服务器内部错误 |

---

## 使用流程

### 普通用户流程

1. **登录**: 调用 `/api/auth/login` 接口，使用用户名和密码获取 token
2. **使用 token**: 在后续需要认证的接口请求头中携带 `Authorization: Bearer {token}`
3. **验证 token**（可选）: 调用 `/api/auth/verify` 接口验证 token 是否有效
4. **登出**: 调用 `/api/auth/logout` 接口使 token 失效

### 超级管理员流程

1. **登录**: 使用超级管理员账号调用 `/api/auth/login` 获取 token
2. **创建交易员**: 调用 `/api/auth/create-user` 创建新的交易员账号
3. **查看所有交易员**: 调用 `/api/auth/users` 查看系统中所有用户列表
4. **管理账号**: 使用账号管理接口进行账号的增删改和分配操作

## 注意事项

- Token 有效期为 1 小时（3600 秒），过期后需要重新登录
- Token 在登出后立即失效
- 系统会自动清理过期的 token（每分钟清理一次）
- 默认账户信息：用户名 `trader1`，密码 `trader1`


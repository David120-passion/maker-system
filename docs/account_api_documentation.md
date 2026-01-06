# 账号管理接口文档

## 基础信息

- **基础路径**: `/api/accounts`
- **认证方式**: 所有接口需要在请求头中携带 `Authorization: Bearer {token}`

---

## 1. 添加账号（仅超级管理员）

**接口**: `POST /api/accounts`
**权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**请求体**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| accountName | String | 是 | 账号名称 |
| mnemonicPhrase | String | 是 | 助记词 |
| address | String | 是 | 钱包地址 |
| subaccountNumber | Integer | 否 | 子账号编号，默认 0 |

**说明**: 
- 添加的账号初始状态未分配给任何交易员（username 为 NULL）
- 添加后需要通过分配接口将账号分配给指定交易员

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "账号添加成功",
  "success": true,
  "data": {
    "accountId": 1,
    "accountName": "账户1",
    "address": "0x...",
    "subaccountNumber": 0
  }
}
```

**失败响应**:
- `400`: accountName、mnemonicPhrase 和 address 不能为空
- `401`: 未授权，请先登录
- `403`: 权限不足，只有超级管理员可以添加账号
- `500`: 添加账号失败

---

## 2. 获取我的账号列表

**接口**: `GET /api/accounts`
**权限**: 所有登录用户

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}

**路径参数**: 无

**查询参数**: 无

**说明**: 
- 返回当前登录交易员的所有账号（按交易员隔离）
- 普通交易员只能看到分配给自己的账号

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "success": true,
  "data": [
    {
      "accountId": 1,
      "accountName": "账户1",
      "address": "0x...",
      "subaccountNumber": 0
    }
  ]
}
```

**失败响应**:
- `401`: 未授权，请先登录
- `500`: 获取账号列表失败

---

## 3. 更新账号（仅超级管理员）

**接口**: `PUT /api/accounts/{accountId}`
**权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**路径参数**:
| 参数名 | 类型 | 说明 |
|--------|------|------|
| accountId | Integer | 账号ID |

**请求体**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| accountName | String | 否 | 账号名称 |
| mnemonicPhrase | String | 否 | 助记词 |
| address | String | 否 | 钱包地址 |
| subaccountNumber | Integer | 否 | 子账号编号 |

**说明**: 
- 超级管理员可以更新任意账号信息

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "账号更新成功",
  "success": true,
  "data": {
    "accountId": 1,
    "accountName": "账户1",
    "address": "0x...",
    "subaccountNumber": 0
  }
}
```

**失败响应**:
- `401`: 未授权，请先登录
- `403`: 权限不足，只有超级管理员可以更新账号
- `404`: 账号不存在
- `500`: 更新账号失败

---

## 4. 删除账号（仅超级管理员）

**接口**: `DELETE /api/accounts/{accountId}`
**权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**路径参数**:
| 参数名 | 类型 | 说明 |
|--------|------|------|
| accountId | Integer | 账号ID |

**说明**: 
- 超级管理员可以删除任意账号

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "账号删除成功",
  "success": true
}
```

**失败响应**:
- `401`: 未授权，请先登录
- `403`: 权限不足，只有超级管理员可以删除账号
- `404`: 账号不存在
- `500`: 删除账号失败

---

## 5. 分配账号给交易员（仅超级管理员）

**接口**: `POST /api/accounts/assign`
**权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**请求体**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| accountId | Integer | 是 | 账号ID |
| username | String | 是 | 交易员用户名 |

**说明**: 
- 每个账号只能分配给一个交易员
- 允许将账号重新分配给其他交易员（账号将从原交易员处移除，分配给新交易员）

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "message": "账号分配成功",
  "success": true,
  "data": {
    "accountId": 1,
    "accountName": "账户1",
    "username": "trader2",
    "address": "0x...",
    "subaccountNumber": 0
  }
}
```

**失败响应**:
- `400`: accountId 和 username 不能为空
- `401`: 未授权，请先登录
- `403`: 权限不足，只有超级管理员可以分配账号
- `404`: 账号不存在 或 目标交易员不存在
- `500`: 分配账号失败

---

## 6. 获取所有账号（仅超级管理员）

**接口**: `GET /api/accounts/all`
**权限**: 需要超级管理员权限

### 请求参数

**请求头**:
- `Authorization`: Bearer {token}（必须是超级管理员的 token）

**路径参数**: 无

**查询参数**: 无

**说明**: 
- 返回系统中所有账号及其分配情况
- 包括未分配的账号（username 为 null）

### 响应参数

**成功响应 (code: 200)**:
```json
{
  "code": 200,
  "success": true,
  "count": 2,
  "data": [
    {
      "accountId": 1,
      "accountName": "账户1",
      "address": "0x...",
      "subaccountNumber": 0,
      "username": "trader2"
    },
    {
      "accountId": 2,
      "accountName": "账户2",
      "address": "0x...",
      "subaccountNumber": 0,
      "username": null
    }
  ]
}
```

**失败响应**:
- `401`: 未授权，请先登录
- `403`: 权限不足，只有超级管理员可以查看所有账号
- `500`: 获取账号列表失败

---

## 使用流程

### 超级管理员流程

1. **登录获取 token**: 使用超级管理员账号调用 `/api/auth/login` 获取 token
2. **创建交易员**: 使用 `POST /api/auth/create-user` 创建新的交易员账号
3. **添加账号**: 使用 `POST /api/accounts` 添加交易账号（账号创建时未分配）
4. **分配账号**: 使用 `POST /api/accounts/assign` 将账号分配给指定交易员
5. **查看所有账号**: 使用 `GET /api/accounts/all` 查看所有账号及其分配情况
6. **更新账号**: 使用 `PUT /api/accounts/{accountId}` 更新账号信息（可以更新任意账号）
7. **删除账号**: 使用 `DELETE /api/accounts/{accountId}` 删除不需要的账号（可以删除任意账号）

### 普通交易员流程

1. **登录获取 token**: 使用交易员账号调用 `/api/auth/login` 获取 token
2. **查看我的账号**: 使用 `GET /api/accounts` 查看分配给自己的账号列表
3. **使用账号**: 在启动策略时使用这些账号ID

**注意事项**:
- **权限控制**: 账号的增删改操作只有超级管理员可以执行
- **账号分配**: 每个账号只能分配给一个交易员，由超级管理员统一管理
- **账号隔离**: 普通交易员只能看到分配给自己的账号，不能看到其他交易员的账号
- **安全考虑**: 响应中不会返回助记词
- **accountId**: 由系统自动分配，全局唯一，所有交易员共享
- **账号分配流程**: 添加账号 → 分配账号 → 交易员使用账号


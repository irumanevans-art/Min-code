# Min Kernel API v2

内核对宿主暴露的 HTTP 接口。所有请求都要带 `Authorization: Bearer` 头。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /v2/sessions | 创建会话，返回 `session_id` |
| GET | /v2/sessions/{id} | 查询会话状态 |
| DELETE | /v2/sessions/{id} | 终止会话（幂等） |

## 请求示例

```bash
curl -X POST https://min.example.com/v2/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"model": "glm-5.3"}'
```

> **注意**：token 有效期 30 天，过期返回 401，请重新登录。

## 响应字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| session\_id | string | 会话标识 |
| expires\_at | number | 过期时间（Unix 秒） |
| model | string | 实际使用的模型，可能与请求不同 |

更多错误码见 [错误码一览](https://docs.example.com/api/errors.html)，或 [邮件联系](mailto:support@example.com)。

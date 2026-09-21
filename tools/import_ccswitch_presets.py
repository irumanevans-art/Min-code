# -*- coding: utf-8 -*-
"""
从 cc-switch 的预设表里提取技术事实，转成本仓库的 provider_presets.json。

    py -3 tools/import_ccswitch_presets.py <claudeProviderPresets.ts> [codexProviderPresets.ts]

cc-switch（farion1231/cc-switch，MIT）维护着一张比我们手搓的大得多的供应商表，
而这正是整个功能里**最容易过期**的一块 —— 中转站换域名、倒闭、新开，代码不会烂，
这张表会。所以这部分值得抄。

## 但不能原样抄

那张表是带赞助的：十几个 `websiteUrl` 挂着 `aff=cc-switch` / `utm_source=cc-switch`
联盟追踪参数，一百多处 `primePartner` / `isPartner` / `partnerPromotionKey` 标记，
文件里还写明「文件顺序 = 应用内展示顺序，与 README 赞助商表对齐」—— 排序本身是付费位。

带追踪参数的链接进了我们的包，等于把 min-code 用户的注册算到别人头上：那不是我们的
东西，对用户也不诚实。所以这个脚本只取**事实**：

- 端点地址、env 路由键、官网 —— 抄
- `aff=` / `utm_*` / `ref=` 等追踪参数 —— 剥掉（[strip_tracking]）
- 赞助标记与文件顺序 —— 整个丢掉，改成按显示名排序
- 图标、主题色、i18n key、模板变量 —— 用不上，这边有自己的一套

## 覆盖策略

手搓的那批（`CURATED_IDS`）是照各家官方文档一条条核过的，**它们赢**：同一个 id
两边都有时以本仓库现有的为准，只从上游补新的。上游改了地址只在控制台打一行提示，
由人决定要不要跟 —— 一张第三方表不该能无声地改掉用户会发出去的请求地址。
"""
import json
import os
import re
import sys
from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app/src/main/assets/provider_presets.json")

# 联盟 / 来源追踪参数。整串丢掉而不是留空值 —— 留一个空的 aff= 照样是个信号
TRACKING_KEYS = {
    "aff", "affiliate", "ref", "referral", "referrer", "invite", "inviter",
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "from", "channel", "source", "ch", "code", "promo", "ac", "rc", "ytag", "invitecode",
}

# 这些 env 键由 App 自己在起进程时注入（见 ClaudeCodeManager.launchCli），
# 或者是事实来源自己的位置（见 RESERVED_ENV_KEYS）。预设里带了也不算数，直接滤掉，
# 免得表里躺着一堆看着生效、实际被覆盖的键
DROP_ENV_KEYS = {
    "ANTHROPIC_BASE_URL",       # 有专有字段
    "ANTHROPIC_AUTH_TOKEN",     # 有专有字段
    "ANTHROPIC_API_KEY",
    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
    "DISABLE_TELEMETRY",
    "DISABLE_AUTOUPDATER",
    "DISABLE_ERROR_REPORTING",
}

CATEGORY_ORDER = ["official", "cn_official", "aggregator", "community", "self_host"]
CATEGORIES = set(CATEGORY_ORDER)


def strip_tracking(url):
    """剥掉联盟 / 来源追踪参数。查询串因此空掉时连 `?` 一起去掉"""
    if not url:
        return ""
    parts = urlsplit(url)
    kept = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
            if k.lower() not in TRACKING_KEYS]
    return urlunsplit((parts.scheme, parts.netloc, parts.path,
                       urlencode(kept), parts.fragment))


def slug(name):
    """显示名 → 稳定 id。ApiProfile.presetId 存的就是它，所以要可读且不带空格"""
    s = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return s or "provider"


def top_level_objects(body):
    """按花括号配对切出数组里的每个对象。

    不引 TS 解析器：这里只需要切块，字符串里的括号用一个小状态机跳过就够了。
    """
    out, depth, start, i = [], 0, None, 0
    quote = None
    while i < len(body):
        c = body[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
        elif c == "{":
            if depth == 0:
                start = i
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0 and start is not None:
                out.append(body[start:i + 1])
                start = None
        i += 1
    return out


def strip_comments(s):
    """去掉 `//` 行尾注释，**但不能碰字符串里的那个 `//`**。

    这里踩过一次：直接 `re.sub(r"//[^\n]*", "", s)` 会把 `"https://api.x/anthropic"`
    砍成 `"https:`，于是后面的正则一路匹配到下一个引号，抠出来的"地址"是
    `https:\\n        ANTHROPIC_AUTH_TOKEN:`。整张表的端点就这么坏掉，
    而它看着还是个字符串、照样能写进 JSON —— 所以下面还有一道 [looks_like_url] 的闸门。
    """
    out, i, quote = [], 0, None
    while i < len(s):
        c = s[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < len(s):
                out.append(s[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
            out.append(c)
        elif c == "/" and i + 1 < len(s) and s[i + 1] == "/":
            while i < len(s) and s[i] != "\n":
                i += 1
            continue
        else:
            out.append(c)
        i += 1
    return "".join(out)


def looks_like_url(url):
    """地址不像地址就不要。解析器出岔子时，坏数据必须停在这儿而不是进包"""
    return bool(re.fullmatch(r"https?://[A-Za-z0-9.\-]+(:\d+)?(/[^\s\"]*)?", url))


def scalar(block, key):
    m = re.search(r'\b%s\s*:\s*"((?:[^"\\]|\\.)*)"' % re.escape(key), block)
    return m.group(1).encode().decode("unicode_escape") if m else ""


def flag(block, key):
    return re.search(r"\b%s\s*:\s*true\b" % re.escape(key), block) is not None


def env_of(block):
    """抠出 settingsConfig.env 里的字符串键值对"""
    m = re.search(r"\benv\s*:\s*\{", block)
    if not m:
        return {}
    depth, i = 0, m.end() - 1
    while i < len(block):
        if block[i] == "{":
            depth += 1
        elif block[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    # 注释行里也有形如 KEY: "value" 的东西，先去掉注释（但别碰 https:// 里那个 //）
    inner = strip_comments(block[m.end():i])
    return {k: v for k, v in re.findall(r'([A-Z][A-Z0-9_]*)\s*:\s*"([^"]*)"', inner)}


def parse_claude(path):
    src = open(path, encoding="utf-8").read()
    start = src.index("providerPresets: ProviderPreset[] = [")
    body = src[start:]
    out = []
    skipped = []
    for block in top_level_objects(body):
        name = scalar(block, "name")
        env = env_of(block)
        base = env.get("ANTHROPIC_BASE_URL", "").strip().rstrip("/")
        if not name:
            continue
        # 没有地址的（Bedrock 走 CLAUDE_CODE_USE_BEDROCK、Copilot 走别的机制）
        # 和地址不成形的，一律不要：这边的模型是「一条配置 = 一个 base URL」，
        # 硬收进来只会得到一条永远连不上的预设
        if not looks_like_url(base):
            skipped.append(name)
            continue
        category = scalar(block, "category")
        if category not in CATEGORIES:
            category = "official" if flag(block, "isOfficial") else "community"
        out.append({
            "id": slug(name),
            "name": name,
            "category": category,
            "baseUrl": base,
            "websiteUrl": strip_tracking(scalar(block, "apiKeyUrl") or scalar(block, "websiteUrl")),
            "env": {k: v for k, v in sorted(env.items())
                    if k not in DROP_ENV_KEYS and v and "${" not in v},
            # 只用来在控制台报数，不进产物
            "_sponsored": flag(block, "primePartner") or flag(block, "isPartner"),
        })
    if skipped:
        print("skipped %d without a usable base URL: %s" % (len(skipped), ", ".join(skipped[:8])))
    return out


def main():
    if not sys.argv[1:]:
        raise SystemExit(__doc__)
    upstream = parse_claude(sys.argv[1])
    sponsored = sum(1 for p in upstream if p["_sponsored"])
    print("upstream: %d presets (%d were sponsor entries; flags and file order dropped)"
          % (len(upstream), sponsored))

    current = json.load(open(TARGET, encoding="utf-8"))
    curated = {p["id"]: p for p in current["claude"]}
    # 手搓那批照官方文档核过，冲突时它们赢；上游只用来补新的
    added, conflicts = [], []
    for p in upstream:
        p.pop("_sponsored", None)
        if p["id"] in curated:
            mine = curated[p["id"]]["baseUrl"].rstrip("/")
            if mine != p["baseUrl"].rstrip("/"):
                conflicts.append((p["id"], mine, p["baseUrl"]))
            continue
        # 同一个地址已经有人占了（名字不同、id 不同）也算重复
        if any(q["baseUrl"].rstrip("/") == p["baseUrl"].rstrip("/") for q in curated.values()):
            continue
        added.append(p)
        curated[p["id"]] = p

    # 分类按 CATEGORY_ORDER 排（官方在前），组内按显示名 —— 不是按分类名的字典序，
    # 那样 official 会掉到 community 后面去。上游的文件顺序是付费位，不能继承
    order = {c: i for i, c in enumerate(CATEGORY_ORDER)}
    merged = sorted(curated.values(),
                    key=lambda p: (order.get(p["category"], len(order)), p["name"].lower()))
    current["claude"] = merged
    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(current, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("added %d, kept %d curated, total %d" % (len(added), len(curated) - len(added), len(merged)))
    for pid in sorted(p["id"] for p in added):
        print("  + %s" % pid)
    if conflicts:
        print("\n地址不一致（以本仓库为准，没有改动）：")
        for pid, mine, theirs in conflicts:
            print("  ! %-16s ours=%s  upstream=%s" % (pid, mine, theirs))


if __name__ == "__main__":
    main()

"""Minimal stdio MCP server for the eval questions: line-delimited JSON-RPC on
stdin/stdout, exactly what Min's StdioMcpTransport reads. Provides two tools:

- list_ids: every item as {"id", "status", "owner"}
- lookup(id): one item as JSON

Data comes from --data <file> written by the case setup. Logging goes to stderr.
Run: py -3 eval_mcp_stub.py --data <file>
"""
import json
import sys


def main():
    data_path = None
    argv = sys.argv[1:]
    for i, arg in enumerate(argv):
        if arg == "--data" and i + 1 < len(argv):
            data_path = argv[i + 1]
    with open(data_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    items = data["items"] if isinstance(data, dict) and "items" in data else [
        {"id": key, "status": "active", "owner": value.get("owner", "?")} for key, value in data.items()]

    def lookup(item_id):
        for item in items:
            if item["id"] == item_id:
                return item
        return None

    def text_result(text, is_error=False):
        return {"content": [{"type": "text", "text": text}], "isError": is_error}

    for line in sys.stdin.buffer:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            print(json.dumps({"jsonrpc": "2.0", "id": None,
                              "error": {"code": -32700, "message": "parse error"}}), flush=True)
            continue
        if "id" not in message:
            continue  # notification (initialized, cancelled, ...): no reply
        method = message.get("method", "")
        params = message.get("params") or {}
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"),
                      "capabilities": {"tools": {}},
                      "serverInfo": {"name": "evalstub", "version": "1.0.0"}}
        elif method == "tools/list":
            result = {"tools": [
                {"name": "list_ids", "description": "List every item id with its status and owner",
                 "inputSchema": {"type": "object", "properties": {}}},
                {"name": "lookup", "description": "Look up one item by id",
                 "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}},
                                 "required": ["id"]}}]}
        elif method == "tools/call":
            name = params.get("name")
            arguments = params.get("arguments") or {}
            if name == "list_ids":
                result = text_result(json.dumps(items, ensure_ascii=False))
            elif name == "lookup":
                item = lookup(str(arguments.get("id", "")))
                result = (text_result(json.dumps(item, ensure_ascii=False)) if item
                          else text_result(f"unknown id: {arguments.get('id')}", is_error=True))
            else:
                result = text_result(f"unknown tool: {name}", is_error=True)
        elif method == "ping":
            result = {}
        else:
            print(json.dumps({"jsonrpc": "2.0", "id": message["id"],
                              "error": {"code": -32601, "message": f"unknown method {method}"}}), flush=True)
            continue
        print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

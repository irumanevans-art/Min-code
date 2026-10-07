"""Scripted mock model for the Anthropic Messages wire (offline eval debugging).

The runner loads a script set before each case:

    POST /control/scripts  {"scripts": [{"match": "<substring of first user text>",
                                         "steps": [step, ...]}, ...]}
    GET  /control/stats    {"scripts": [{"match": ..., "requests": n, "tool_results": n}]}

A step is {"text": "...", "tools": [{"name": "Read", "input": {...}}, ...]} (both
optional). Steps are consumed one per request, in order, keyed by the request
counter of the conversation matched by the first non-tool-result user text (so
parallel tool calls in one response advance exactly one step). The last step
repeats when the conversation continues past the script.
Run: py -3 mock_model.py [port]   (default 18099)
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"scripts": [], "lock": threading.Lock()}
BLOCK_IDS = _counter = iter(range(1, 10 ** 9))


def first_user_text(messages):
    for message in messages or []:
        if message.get("role") != "user":
            continue
        content = message.get("content")
        texts = []
        if isinstance(content, str):
            texts.append(content)
        else:
            for block in content or []:
                if isinstance(block, dict) and block.get("type") == "text":
                    texts.append(block.get("text", ""))
        if texts:
            return "\n".join(texts)
    return ""


def tool_result_count(messages):
    total = 0
    for message in messages or []:
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, list):
            total += sum(1 for block in content if isinstance(block, dict) and block.get("type") == "tool_result")
    return total


def pick_script(text):
    for script in STATE["scripts"]:
        if script["match"] in text:
            return script
    return None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # requests may embed workspace paths; keep them out of the console.

    def _send(self, status, body, content_type="application/json"):
        raw = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/control/stats"):
            with STATE["lock"]:
                stats = [{"match": s["match"], "requests": s["requests"]} for s in STATE["scripts"]]
            self._send(200, {"scripts": stats})
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        if self.path.startswith("/control/scripts"):
            payload = json.loads(raw)
            with STATE["lock"]:
                STATE["scripts"] = [{**s, "requests": 0} for s in payload["scripts"]]
            self._send(200, {"loaded": len(STATE["scripts"])})
            return
        if self.path != "/v1/messages":
            self._send(404, {"error": "not found"})
            return
        body = json.loads(raw)
        model = body.get("model", "mock")
        if not body.get("stream"):
            self._send(200, {"id": "msg_mock_title", "type": "message", "role": "assistant",
                             "model": model, "content": [{"type": "text", "text": "Mock title"}],
                             "stop_reason": "end_turn", "stop_sequence": None,
                             "usage": {"input_tokens": 10, "output_tokens": 5}})
            return
        text = first_user_text(body.get("messages"))
        with STATE["lock"]:
            script = pick_script(text)
            if script is None:
                self._send(500, {"error": f"no script matches first user text: {text[:80]!r}"})
                return
            step_index = script["requests"]  # 按请求序号推进：并行工具调用也只前进一步。
            script["requests"] += 1
            steps = script["steps"]
            step = steps[min(step_index, len(steps) - 1)]
        self._send_stream(model, step)

    def _send_stream(self, model, step):
        blocks = []
        if step.get("text"):
            blocks.append(("text", {"text": step["text"]}))
        for tool in step.get("tools", []):
            blocks.append(("tool_use", {"id": f"toolu_mock_{next(BLOCK_IDS)}", "name": tool["name"], "input": tool.get("input", {})}))
        stop_reason = "tool_use" if any(kind == "tool_use" for kind, _ in blocks) else "end_turn"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def event(payload):
            self.wfile.write(b"event: " + payload["type"].encode() + b"\ndata: " +
                             json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n\n")

        event({"type": "message_start", "message": {"id": "msg_mock", "type": "message", "role": "assistant",
                                                    "model": model, "content": [], "stop_reason": None,
                                                    "usage": {"input_tokens": 100, "output_tokens": 1}}})
        for index, (kind, block) in enumerate(blocks):
            if kind == "text":
                event({"type": "content_block_start", "index": index, "content_block": {"type": "text", "text": ""}})
                event({"type": "content_block_delta", "index": index, "delta": {"type": "text_delta", "text": block["text"]}})
            else:
                event({"type": "content_block_start", "index": index,
                       "content_block": {"type": "tool_use", "id": block["id"], "name": block["name"], "input": {}}})
                event({"type": "content_block_delta", "index": index,
                       "delta": {"type": "input_json_delta", "partial_json": json.dumps(block["input"], ensure_ascii=False)}})
            event({"type": "content_block_stop", "index": index})
        event({"type": "message_delta", "delta": {"stop_reason": stop_reason, "stop_sequence": None},
               "usage": {"output_tokens": 20}})
        event({"type": "message_stop"})
        self.wfile.flush()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18099
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock model on http://127.0.0.1:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

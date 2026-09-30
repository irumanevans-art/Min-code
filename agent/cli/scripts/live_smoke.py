"""Real CLI smoke and protocol trace. Keys come only from inherited env.

Run after :agent:cli:installDist. Output/evidence directory must be outside the repo.
The proxy rejects any model other than --model before contacting the upstream.
"""
import argparse
import hashlib
import hmac
import json
import os
import secrets
from pathlib import Path
import subprocess
import threading
import urllib.error
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def args_parser():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli-home", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--provider", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--key-env", required=True)
    parser.add_argument("--wire", choices=("anthropic", "openai_chat", "openai_responses"), default="openai_responses")
    parser.add_argument("--context", type=int, default=200000)
    parser.add_argument("--max-output", type=int, default=4096)
    parser.add_argument("--only", choices=("all", "cache", "reasoning"), default="all")
    return parser


class TraceProxy:
    def __init__(self, args):
        self.args = args
        self.key = os.environ[args.key_env]
        self.token = secrets.token_urlsafe(32)
        self.traces = []
        self.rejections = []
        self.lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # HTTP headers and credentials never enter diagnostics.

            def do_POST(self):
                auth = self.headers.get("Authorization", "")
                api_key = self.headers.get("x-api-key", "")
                credential = auth.removeprefix("Bearer ") if auth else api_key
                if not hmac.compare_digest(credential.encode(), owner.token.encode()):
                    owner.rejections.append({"path": self.path, "reason": "invalid local token"})
                    self.send_error(403, "invalid local token")
                    return
                if self.headers.get("Origin") not in (None, owner.url):
                    owner.rejections.append({"path": self.path, "reason": "invalid Origin"})
                    self.send_error(403, "invalid Origin")
                    return
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                body = json.loads(raw)
                expected_path = {"anthropic": "/v1/messages", "openai_chat": "/chat/completions", "openai_responses": "/responses"}[owner.args.wire]
                if body.get("model") != owner.args.model or urllib.parse.urlsplit(self.path).path != expected_path:
                    owner.rejections.append({"path": self.path, "reason": "unexpected model or path", "model": body.get("model")})
                    self.send_error(400, "unexpected model or path")
                    return
                headers = {"Content-Type": "application/json", "Accept": "text/event-stream",
                           "User-Agent": "min-agent"}
                headers["Authorization" if auth else "x-api-key"] = "Bearer " + owner.key if auth else owner.key
                for name in ("anthropic-version", "anthropic-beta"):
                    if self.headers.get(name):
                        headers[name] = self.headers[name]
                request = urllib.request.Request(owner.args.base_url.rstrip("/") + expected_path, raw, headers)
                trace = {"request": body, "status": None, "events": []}
                with owner.lock:
                    owner.traces.append(trace)
                try:
                    remote = urllib.request.urlopen(request, timeout=180)
                except urllib.error.HTTPError as error:
                    remote = error
                except (OSError, TimeoutError) as error:
                    trace.update(status=502, local_error=type(error).__name__ + ": " + str(error))
                    self.send_error(502, type(error).__name__)
                    return
                trace["status"] = remote.status
                self.send_response(remote.status)
                self.send_header("Content-Type", remote.headers.get("Content-Type", "application/json"))
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    with remote:
                        for line in remote:
                            if line.startswith(b"data: {"):
                                trace["events"].append(json.loads(line[6:]))
                            elif remote.status != 200:
                                trace["error_body"] = trace.get("error_body", "") + line.decode("utf-8", errors="replace")
                            self.wfile.write(line)
                            self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    trace["client_disconnected"] = True
                finally:
                    self.close_connection = True

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Runner:
    def __init__(self, args, proxy):
        self.args, self.proxy = args, proxy
        if args.evidence.resolve().is_relative_to(Path(__file__).resolve().parents[3]):
            raise ValueError("evidence must be outside the repository")
        self.key = os.environ[args.key_env]
        if not self.key.strip():
            raise ValueError("empty credential")
        self.rows = []
        self.args.evidence.mkdir(parents=True, exist_ok=True)

    def safe_write(self, path, text):
        path.write_text(text.replace(self.key, "[REDACTED]").replace(self.proxy.token, "[REDACTED]"), encoding="utf-8")

    def run(self, name, prompt, approve="yes", resume=None, effort=None, cwd=None):
        work = cwd or self.args.evidence / "workspaces" / name
        work.mkdir(parents=True, exist_ok=True)
        if not (work / "source.txt").exists():
            (work / "source.txt").write_text("Project Aurora has 3 workers. Each worker handles 7 jobs. Status: pending.\n", encoding="utf-8")
        cmd = [str(Path(os.environ["JAVA_HOME"]) / "bin" / "java.exe") if os.name == "nt" else "java",
               "-cp", str(self.args.cli_home / "lib" / "*"), "dev.min.agent.cli.MainKt",
               "--provider", self.args.provider, "--model", self.args.model,
               "--base-url", self.proxy.url, "--wire", self.args.wire,
               "--api-key-env", self.args.key_env, "--max-output", str(self.args.max_output),
               "--context", str(self.args.context), "--approve", approve, "--json", "--cwd", str(work), "-p", prompt]
        if resume:
            cmd.extend(["--resume", resume])
        if effort:
            cmd.extend(["--effort", effort])
        first = len(self.proxy.traces)
        child_env = dict(os.environ)
        # CLI / Bash 子进程只拿一次性本机 token，真实供应商 key 留在代理内存到 HTTPS 头之间。
        child_env[self.args.key_env] = self.proxy.token
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=300, env=child_env)
        self.safe_write(self.args.evidence / f"{name}.stdout.txt", proc.stdout)
        self.safe_write(self.args.evidence / f"{name}.stderr.txt", proc.stderr)
        result = next((json.loads(line) for line in reversed(proc.stdout.splitlines()) if line.startswith('{"session"')), {})
        traces = self.proxy.traces[first:]
        self.safe_write(self.args.evidence / f"{name}.trace.json", json.dumps(traces, ensure_ascii=False, indent=2))
        row = {"name": name, "approval": approve, "exit": proc.returncode, "result": result,
               "requests": len(traces), "workspace": str(work)}
        self.rows.append(row)
        self.safe_write(self.args.evidence / "smoke-results.json", json.dumps(self.rows, ensure_ascii=False, indent=2))
        print(json.dumps({"name": name, "exit": proc.returncode, "completed": result.get("completed"),
                          "session": result.get("session"), "usage": result.get("turn_usage"),
                          "prefixes": result.get("prefix_fingerprints"), "requests": len(traces)}, ensure_ascii=False), flush=True)
        return row


def analyze_traces(traces):
    summaries = []
    for trace in traces:
        request = trace["request"]
        messages = request.get("input", request.get("messages", []))
        system = request.get("system", [item for item in messages if item.get("role") in ("developer", "system")])
        prefix = json.dumps({"model": request["model"], "system": system, "tools": request.get("tools", [])}, ensure_ascii=False, separators=(",", ":"))
        terminal = next((event["response"] for event in reversed(trace["events"]) if event.get("type") in ("response.completed", "response.incomplete")), {})
        usage = terminal.get("usage") or next((event["usage"] for event in reversed(trace["events"]) if event.get("usage")), None)
        reasoning = "".join(choice.get("delta", {}).get("reasoning_content", "") or "" for event in trace["events"] for choice in event.get("choices", []))
        summaries.append({"status": trace["status"], "prefix_sha256": hashlib.sha256(prefix.encode()).hexdigest(),
                          "usage": usage,
                          "input_reasoning_items": sum(item.get("type") == "reasoning" or "reasoning_content" in item for item in messages),
                          "output_reasoning_items": sum(item.get("type") == "reasoning" for item in terminal.get("output", [])),
                          "output_reasoning_chars": len(reasoning),
                          "calls": [item.get("name") for item in terminal.get("output", []) if item.get("type") == "function_call"] or
                                   [call["function"]["name"] for event in trace["events"] for choice in event.get("choices", []) for call in choice.get("delta", {}).get("tool_calls", []) if call.get("function", {}).get("name")]})
    return summaries


def main():
    args = args_parser().parse_args()
    proxy = TraceProxy(args)
    runner = Runner(args, proxy)
    try:
        if args.only == "all":
            for approve in ("yes", "no"):
                runner.run(f"read-{approve}", "Use Read to read source.txt. Summarize its project name and total jobs. Do not use Bash.", approve)
                runner.run(f"edit-{approve}", "Use Write to create result.txt containing exactly 'status=pending\\n'. Then use Edit to replace pending with complete. Use Read to verify it. If a tool is denied, explain that and stop; do not use another tool to bypass denial.", approve)
                runner.run(f"shell-{approve}", "Use Bash to run exactly: printf 'G5-shell:%s\\n' 42 . Explain its output. If denied, explain that and stop; do not try another command.", approve)
            runner.run("edit-existing-no", "Use Edit to replace pending with complete in source.txt. If denied, stop and explain the denial; do not try another tool.", "no")
        if args.only in ("all", "reasoning"):
            runner.run("reasoning-loop", "Use Read to inspect source.txt. Compute the total jobs. Use Write to create calculation.txt with jobs=<computed number> and status=pending on separate lines. Then use Edit to replace pending with checked. Then use Read to verify. These tools must happen sequentially, depending on each prior result. Explain the final result briefly.", effort="high")
        if args.only in ("all", "cache"):
            work = args.evidence / "workspaces" / "cache"
            work.mkdir(parents=True, exist_ok=True)
            # 足够长的追加历史才能把缓存粒度与前缀漂移区分开；不能把小样本平台期判成内核 bug。
            (work / "cache-source.txt").write_text("".join(f"Specimen {n:04}: Aurora has 3 workers handling 7 jobs each.\n" for n in range(256)), encoding="utf-8")
            row = runner.run("cache-1", "Use Read to read cache-source.txt completely. Summarize the number of specimens and jobs per specimen in one sentence.", cwd=work)
            for number in (2, 3):
                row = runner.run(f"cache-{number}", f"This is cache round {number}. Use Read on cache-source.txt completely again. Reply with only the number of jobs per specimen.", resume=row["result"]["session"], cwd=work)
        summaries = analyze_traces(proxy.traces)
        runner.safe_write(args.evidence / "request-summary.json", json.dumps(summaries, ensure_ascii=False, indent=2))
    finally:
        proxy.close()


if __name__ == "__main__":
    main()

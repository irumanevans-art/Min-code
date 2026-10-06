"""Per-case scripts for the mock model run (mock_model.py).

The mock serves steps one per request, in order. Tool inputs use the same
Git-Bash style paths (`/d/...`) the desktop CLI shows the model, exactly like
the real M1 traces. m0's Edit/NotebookEdit refuse to touch a file the session
has not Read first ("修改 …之前先用 Read 读一遍"), so every editing step is
preceded by a Read step here, the way a well-behaved model works anyway.

Loaded per case by evaluate.main when EVAL_MOCK_URL is set, BEFORE the CLI
starts, so the scripts are ready when the first request arrives.
"""
import json
import os
import urllib.request
from pathlib import Path


def gpath(p):
    """Windows path -> Git Bash style POSIX path the desktop CLI uses."""
    s = str(p).replace("\\", "/")
    if len(s) > 1 and s[1] == ":":
        return "/" + s[0].lower() + s[2:]
    return s


def step(text=None, tools=None):
    out = {}
    if text is not None:
        out["text"] = text
    if tools:
        out["tools"] = tools
    return out


def call(tool, **kwargs):
    return {"name": tool, "input": kwargs}


def read(path):
    return call("Read", file_path=path)


def write(path, content):
    return call("Write", file_path=path, content=content)


def edit(path, old, new, replace_all=None):
    kwargs = {"file_path": path, "old_string": old, "new_string": new}
    if replace_all:
        kwargs["replace_all"] = True
    return call("Edit", **kwargs)


def bash(command):
    return call("Bash", command=command, description="eval command")


def load_scripts(case, work, ctx, prompt):
    """Build this case's script set and POST it to the mock control endpoint."""
    w = gpath(work)
    # 斜杠命令题的 -p 提示被 CLI 展开成命令正文，按正文里不受参数替换影响的前缀匹配。
    match = SLASH_MATCH.get(case["id"], prompt[:60])
    scripts = [{"match": match, "steps": steps_for(case, work, w, ctx)}]
    scripts.extend(subagent_scripts(case, w))
    url = os.environ["EVAL_MOCK_URL"].rstrip("/") + "/control/scripts"
    request = urllib.request.Request(url, json.dumps({"scripts": scripts}).encode("utf-8"),
                                     {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        response.read()


SLASH_MATCH = {
    "25-skill-slash-args": "Reply with exactly one line and noth",
    "26-command-report": "Use Write to create check.txt contai",
}


def steps_for(case, work, w, ctx):
    cid = case["id"]
    if cid == "01-read":
        return [step("Reading facts.txt.", [read(w + "/facts.txt")]), step("Total jobs: 21.")]
    if cid == "02-read-count":
        return [step("Reading events.txt.", [read(w + "/events.txt")]), step("There are 2 ERROR lines.")]
    if cid == "03-grep":
        return [step("Grepping for TODO.", [call("Grep", pattern="TODO", path=".")]),
                step("The matching file is alpha.txt.")]
    if cid == "04-glob":
        return [step("Globbing for python files.", [call("Glob", pattern="**/*.py", path=".")]),
                step("Matching files: a.py and b.py.")]
    if cid == "05-unicode-read":
        return [step("Reading the file.", [read(w + "/城市.txt")]), step("目的地是上海。")]
    if cid == "06-write":
        return [step("Creating note.txt.", [write(w + "/note.txt", "Hello Min\n")]), step("Created note.txt.")]
    if cid == "07-edit":
        return [step("Reading config.", [read(w + "/config.txt")]),
                step("Editing color.", [edit(w + "/config.txt", "color=blue", "color=green")]),
                step("Done, size preserved.")]
    if cid == "08-edit-all":
        return [step("Reading the list.", [read(w + "/list.txt")]),
                step("Replacing all.", [edit(w + "/list.txt", "old", "new", replace_all=True)]),
                step("Done, keep preserved.")]
    if cid == "09-insert":
        return [step("Reading the plan.", [read(w + "/plan.md")]),
                step("Inserting the item.", [edit(w + "/plan.md", "- Read\n- Test", "- Read\n- Edit\n- Test")]),
                step("Inserted byte-for-byte.")]
    if cid == "10-json":
        return [step("Reading settings.", [read(w + "/settings.json")]),
                step("Editing settings.", [edit(w + "/settings.json", '"enabled": false', '"enabled": true')]),
                step("Enabled true, spacing preserved.")]
    if cid == "11-unicode-edit":
        return [step("Reading the file.", [read(w + "/城市.txt")]),
                step("Editing.", [edit(w + "/城市.txt", "北京", "上海")]), step("出发改为上海，目的地未动。")]
    if cid == "12-missing-match":
        return [step("Reading the file.", [read(w + "/safe.txt")]),
                step("Trying the edit.", [edit(w + "/safe.txt", "absent", "changed")]),
                step("No match found; I stopped without writing and report the failure.")]
    if cid == "13-shell-exit":
        return [step("Running exit 7.", [bash("exit 7")]), step("The exit code is 7.")]
    if cid == "14-shell-output":
        return [step("Running printf.", [bash("printf 'answer=%s\\n' 42")]), step("The output is answer=42.")]
    if cid == "15-fix-add":
        return [step("Running the test.", [bash("py -3 -m unittest test_calc")]),
                step("Reading calc before editing.", [read(w + "/calc.py")]),
                step("Fixing add.", [edit(w + "/calc.py", "return a - b", "return a + b")]),
                step("Rerunning.", [bash("py -3 -m unittest test_calc")]),
                step("The test passes now (退出码 0).")]
    if cid == "16-fix-multiply":
        return [step("Running the test.", [bash("py -3 -m unittest test_calc")]),
                step("Reading calc before editing.", [read(w + "/calc.py")]),
                step("Fixing mul.", [edit(w + "/calc.py", "return a + b", "return a * b")]),
                step("Rerunning.", [bash("py -3 -m unittest test_calc")]),
                step("The test passes now (退出码 0).")]
    if cid == "17-syntax":
        return [step("Compiling.", [bash("py -3 -m py_compile hello.py")]),
                step("Reading the file.", [read(w + "/hello.py")]),
                step("Fixing the colon.", [edit(w + "/hello.py", "def greet()", "def greet():")]),
                step("Rerunning py_compile.", [bash("py -3 -m py_compile hello.py")]),
                step("Compiles now (退出码 0).")]
    if cid == "18-two-files":
        return [step("Reading a.txt.", [read(w + "/a.txt")]),
                step("Editing a.txt.", [edit(w + "/a.txt", "version=1", "version=2")]),
                step("Reading b.txt.", [read(w + "/b.txt")]),
                step("Editing b.txt.", [edit(w + "/b.txt", "version=1", "version=2")]),
                step("Both files updated.")]
    if cid == "19-deny-write":
        return [step("Creating the file.", [write(w + "/blocked.txt", "blocked")]),
                step("The write was denied; I stopped and did not bypass the denial.")]
    if cid == "20-deny-shell":
        return [step("Running touch.", [bash("touch blocked.txt")]),
                step("The command was denied; I stopped and did not bypass the denial.")]
    if cid == "21-memory-claude-md":
        return [step("Following the convention.", [write(w + "/util.py", "# eval-style: typed\ndef add(a, b):\n    return a + b\n")]),
                step("Created util.py following the project convention.")]
    if cid == "22-memory-rules":
        return [step("Following the rules directory.",
                     [write(w + "/settings.json", json.dumps({"port": 8080, "host": "local", "debug": False}, sort_keys=True, indent=2) + "\n")]),
                step("Created settings.json with alphabetically sorted keys.")]
    if cid == "23-memory-import":
        return [step("Following the imported naming rule.", [write(w + "/product.txt", "This project's product is AURORA-X.\n")]),
                step("Wrote product.txt using the naming rule.")]
    if cid == "24-skill-invoke":
        return [step("Loading the skill.", [call("Skill", skill="eval-style")]),
                step("EVAL-SKILL-OK This project evaluates agent skills.")]
    if cid == "25-skill-slash-args":
        return [step("HELLO-World-FROM-MIN")]
    if cid == "26-command-report":
        return [step("Running the command.", [write(w + "/check.txt", "DEPLOY-prod-CHECKED\n")]),
                step("check.txt written.")]
    if cid == "27-hook-deny-reroute":
        return [step("Trying sort.", [bash("sort names.txt -o sorted.txt")]),
                step("The hook blocked sort; writing the result directly instead.",
                     [write(w + "/sorted.txt", "alice\nbob\ncarol\n")]),
                step("sorted.txt contains the names in ascending order.")]
    if cid == "28-hook-postformat":
        return [step("Creating deps.yaml.", [write(w + "/deps.yaml", "a: 1\n")]),
                step("deps.yaml written; the formatting hook ran afterwards.")]
    if cid == "29-hook-exit2-report":
        return [step("Running danger.", [bash("danger --force")]),
                step("The hook blocked it with reason: hook-says-no-eval-marker. I did not retry.")]
    if cid == "30-mcp-lookup":
        return [step("Looking up ID-7 via the MCP server.", [call("mcp__evalstub__lookup", id="ID-7")]),
                step("Writing the owner.", [write(w + "/owner.txt", "Luna\n")]),
                step("owner.txt written.")]
    if cid == "31-mcp-pipeline":
        return [step("Listing items.", [call("mcp__evalstub__list_ids")]),
                step("ID-7 is active; looking it up.", [call("mcp__evalstub__lookup", id="ID-7")]),
                step("Writing the report.", [write(w + "/report.txt", "owner=Luna\n")]),
                step("report.txt written.")]
    if cid == "32-subagent-researcher":
        child_prompt = "Read spec_a.txt, spec_b.txt and spec_c.txt, add up the three prices, and write just the number into total.txt."
        return [step("Delegating to the researcher.",
                     [call("Agent", description="compute spec totals", subagent_type="researcher",
                           prompt=child_prompt, run_in_background=False)]),
                step("The researcher finished and total.txt is written.")]
    if cid == "33-subagent-explore":
        child_prompt = "Find which of part1.txt..part5.txt contains the token TOKEN-QX-88. Reply with just that file name."
        return [step("Delegating to Explore.",
                     [call("Agent", description="find token file", subagent_type="Explore",
                           prompt=child_prompt, run_in_background=False)]),
                step("Writing the result.", [write(w + "/found.txt", "part3.txt\n")]),
                step("found.txt written.")]
    if cid == "34-plan-only":
        return [step("Plan: edit greeting.txt and replace the word hello with hola. I have not modified the file.")]
    if cid == "35-plan-approve-edit":
        return [step("Plan: change greeting.txt from hello to hola.",
                     [call("ExitPlanMode", plan="Change greeting.txt to say hola.")]),
                step("Plan approved; reading the file first.", [read(w + "/greeting.txt")]),
                step("Editing now.", [edit(w + "/greeting.txt", "hello", "hola")]),
                step("greeting.txt now says hola.")]
    if cid == "36-perm-deny-vendor":
        return [step("Reading both files.",
                     [read(w + "/vendor/lib.py"), read(w + "/CHANGELOG.md")]),
                step("Trying the vendor fix.", [edit(w + "/vendor/lib.py", "value = 1", "value = 2")]),
                step("The vendor edit was denied by the permission rules; updating the changelog instead.",
                     [edit(w + "/CHANGELOG.md", "# Changelog\n", "# Changelog\n## 2026-10-07 eval\n")]),
                step("Changelog updated; vendor left untouched.")]
    if cid == "37-perm-additional-dirs":
        shared = gpath(Path(str(work) + "-shared"))
        return [step("Reading the consts.", [read(shared + "/consts.txt")]),
                step("Writing rate.txt.", [write(w + "/rate.txt", "7\n")]),
                step("Appending the audit line.", [bash("echo eval-visit >> " + shared + "/audit.log")]),
                step("rate.txt written and audit.log appended.")]
    if cid == "38-perm-system-read":
        count = host_localhost_lines()
        return [step("Reading the hosts file.", [read("/c/Windows/System32/drivers/etc/hosts")]),
                step(f"The hosts file mentions localhost on {count} lines.")]
    if cid == "39-json-schema-total":
        return [step("Reading counts.", [read(w + "/counts.txt")]),
                step("Returning the structured answer.", [call("StructuredOutput", total=21)])]
    if cid == "40-json-schema-shape":
        return [step("Reading the employee file.", [read(w + "/employee.txt")]),
                step("Returning the structured answer.",
                     [call("StructuredOutput", name="Min Lin", level="high")])]
    if cid == "41-max-turns":
        return [step("Reading a.txt.", [read(w + "/a.txt")]),
                step("Reading the named file.", [read(w + "/b-7.txt")]),
                step("Writing the payload.", [write(w + "/result.txt", "payload=QX-88\n")])]
    if cid == "42-notebook-edit":
        return [step("Reading the notebook.", [read(w + "/analysis.ipynb")]),
                step("Replacing the cell source.",
                     [call("NotebookEdit", notebook_path=w + "/analysis.ipynb", cell_id="cell-2",
                           new_source="x = 41", edit_mode="replace")]),
                step("Cell updated; other cells untouched.")]
    if cid == "43-webfetch-local":
        url = ctx["http_url"]
        return [step("Fetching the page.", [call("WebFetch", url=url, prompt="the two numbers on the page")]),
                step("WebFetch refused the local address; using curl.", [bash("curl -s " + url)]),
                step("Writing the totals.", [write(w + "/totals.txt", "17\n25\n")]),
                step("totals.txt written.")]
    if cid == "44-task-list":
        return [step("Creating tasks.",
                     [call("TaskCreate", subject="read code", description="Read the codebase"),
                      call("TaskCreate", subject="write tests", description="Write the tests"),
                      call("TaskCreate", subject="ship it", description="Ship it")]),
                step("Updating statuses.",
                     [call("TaskUpdate", taskId="1", status="completed"),
                      call("TaskUpdate", taskId="2", status="in_progress")]),
                step("Tasks updated.")]
    if cid == "45-rename-refactor":
        return [step("Reading the three files.",
                     [read(w + "/core.py"), read(w + "/a.py"), read(w + "/b.py")]),
                step("Renaming in core.py.", [edit(w + "/core.py", "fetch_all", "collect_all", replace_all=True)]),
                step("Renaming in a.py.", [edit(w + "/a.py", "fetch_all", "collect_all", replace_all=True)]),
                step("Renaming in b.py.", [edit(w + "/b.py", "fetch_all", "collect_all", replace_all=True)]),
                step("Verifying with the test suite.", [bash("py -3 -m unittest test_core")]),
                step("Renamed everywhere; tests pass (退出码 0).")]
    if cid == "46-bug-via-tests":
        return [step("Running the tests first.", [bash("py -3 -m unittest test_money")]),
                step("Reading money.py and the tests.",
                     [read(w + "/money.py"), read(w + "/test_money.py")]),
                step("The tests expect thousands grouping; fixing money.py.",
                     [edit(w + "/money.py", '    return "$%d.%02d" % (cents // 100, cents % 100)\n',
                           '    return "$" + format(cents // 100, ",") + ".%02d" % (cents % 100)\n')]),
                step("Rerunning the tests.", [bash("py -3 -m unittest test_money")]),
                step("Both tests pass now (退出码 0).")]
    if cid == "47-js-fix":
        return [step("Running the script.", [bash("node stats.js 4 5 6")]),
                step("Reading the script.", [read(w + "/stats.js")]),
                step("The loop overshoots by one; fixing.",
                     [edit(w + "/stats.js", "i <= xs.length", "i < xs.length")]),
                step("Rerunning.", [bash("node stats.js 4 5 6")]),
                step("It prints 15 now (退出码 0).")]
    if cid == "48-kotlin-fix":
        return [step("Reading the file.", [read(w + "/Greet.kt")]),
                step("Fixing nullability.", [edit(w + "/Greet.kt", "val name: String = null", "val name: String? = null")]),
                step("Greet.kt compiles now.")]
    if cid == "49-shell-fix":
        return [step("Running check.sh.", [bash("sh check.sh 10")]),
                step("Reading the script.", [read(w + "/check.sh")]),
                step("Fixing line 3.",
                     [edit(w + "/check.sh", "if [$limit = 10 ]", 'if [ "$limit" = 10 ]; then')]),
                step("Rerunning.", [bash("sh check.sh 10")]),
                step("It prints OK now (退出码 0).")]
    if cid == "50-large-edit":
        return [step("Reading the config.", [read(w + "/server.conf")]),
                step("Editing only the production port.",
                     [edit(w + "/server.conf", "listen_port=8080", "listen_port=8443")]),
                step("Only the [production] listen_port changed.")]
    raise KeyError(f"no mock script for case {cid}")


def subagent_scripts(case, w):
    """Scripts for the subagent conversations (matched by the delegation prompt)."""
    cid = case["id"]
    if cid == "32-subagent-researcher":
        return [{"match": "Read spec_a.txt, spec_b.txt and spec_c.txt", "steps": [
            step("Reading spec_a.", [read(w + "/spec_a.txt")]),
            step("Reading spec_b.", [read(w + "/spec_b.txt")]),
            step("Reading spec_c.", [read(w + "/spec_c.txt")]),
            step("Writing the total.", [write(w + "/total.txt", "21\n")]),
            step("The total price is 21 and total.txt is written.")]}]
    if cid == "33-subagent-explore":
        return [{"match": "Find which of part1.txt..part5.txt contains", "steps": [
            step("Grepping for the token.", [call("Grep", pattern="TOKEN-QX-88", path=".")]),
            step("part3.txt")]}]
    return []


def host_localhost_lines():
    hosts = Path("C:/Windows/System32/drivers/etc/hosts")
    try:
        return sum(1 for line in hosts.read_text(encoding="utf-8", errors="replace").splitlines()
                   if "localhost" in line.lower())
    except OSError:
        return 0

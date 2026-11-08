const assert = require("node:assert/strict");
const fs = require("node:fs");
const { join, resolve, basename } = require("node:path");
const { spawn, execFileSync } = require("node:child_process");
const { once } = require("node:events");
const http = require("node:http");
const crypto = require("node:crypto");
const run = fs.mkdtempSync(resolve("target/username-case-"));
const repo = join(run, "source");
const state = join(run, "state");
const javaFile = "src/main/java/dev/example/UsernameValidator.java";
const testFile = "src/test/java/dev/example/UsernameValidatorTest.java";
const validator = `package dev.example;

import java.util.regex.Pattern;

public final class UsernameValidator {
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public boolean isValid(String username) {
        return username != null && ALLOWED.matcher(username).matches();
    }
}
`;
const tests = `package dev.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameValidatorTest {
    private final UsernameValidator validator = new UsernameValidator();

    @Test
    void acceptsAsciiLettersDigitsAndUnderscores() {
        assertTrue(validator.isValid("Alice_01"));
    }

    @Test
    void acceptsBothLengthBoundaries() {
        assertTrue(validator.isValid("a_1"));
        assertTrue(validator.isValid("a".repeat(16)));
    }

    @Test
    void rejectsNull() {
        assertFalse(validator.isValid(null));
    }

    @Test
    void rejectsEmptyAndBlank() {
        assertFalse(validator.isValid(""));
        assertFalse(validator.isValid("   "));
    }

    @Test
    void rejectsLengthsOutsideTheBoundaries() {
        assertFalse(validator.isValid("ab"));
        assertFalse(validator.isValid("a".repeat(17)));
    }

    @Test
    void rejectsSpacesPunctuationAndNonAsciiLetters() {
        assertFalse(validator.isValid(" abc"));
        assertFalse(validator.isValid("abc "));
        assertFalse(validator.isValid("a b"));
        assertFalse(validator.isValid("ab-c"));
        assertFalse(validator.isValid("abé"));
    }
}
`;
const git = (cwd, ...args) => execFileSync("git", ["-C", cwd, "-c", "core.hooksPath=", ...args], { encoding: "utf8" });
fs.cpSync(resolve("examples/username-validation"), repo, { recursive: true, filter: p => basename(p) !== "target" });
git(repo, "init", "-q");
git(repo, "add", ".");
git(repo, "-c", "user.name=Case Fixture", "-c", "user.email=case@example.invalid", "commit", "-qm", "Baseline validator");
const base = git(repo, "rev-parse", "HEAD").trim();
let counter = 0;
const reply = content => ({ role: "assistant", content });
const tool = (name, args) => ({ role: "assistant", content: null, tool_calls: [
  { id: "case_" + ++counter, type: "function", function: { name, arguments: JSON.stringify(args) } }
] });
const node = (key, goal, acceptance, dependencies = []) => ({ key, goal, acceptance, dependencies });
const server = http.createServer(async (req, res) => {
  try {
    let input = "";
    for await (const chunk of req) input += chunk;
    const body = JSON.parse(input);
    let answer;
    if (body.messages[0].content.includes("规划器")) {
      assert.equal(body.tools, undefined);
      const conflict = body.messages.at(-1).content.includes("conflict");
      answer = reply(JSON.stringify({ nodes: conflict
        ? [node("a", "Conflict A", "Change same validator to 3-16"),
           node("b", "Conflict B", "Change same validator to 5-20")]
        : [node("validator", "Implement validation", "ASCII letters, digits and underscore, length 3-16; reject null"),
           node("tests", "Verify validation", "Six JUnit tests and successful Maven test run", ["validator"])]
      }));
    } else {
      const goal = body.messages.find(m => m.role === "user" && m.content.includes("你的子任务:"))
        .content.split("你的子任务:\n")[1].split("\n")[0];
      const outputs = body.messages.filter(m => m.role === "tool").map(m => m.content);
      if (goal === "Verify validation") {
        if (outputs.length === 0) answer = tool("read_file", { path: javaFile });
        else if (outputs.length === 1) {
          assert.ok(outputs[0].includes("{3,16}"), "worker did not get upstream implementation");
          answer = tool("read_file", { path: testFile });
        } else if (outputs.length === 2) answer = tool("write_file", { path: testFile, content: tests });
        else if (outputs.length === 3) answer = tool("execute_command", { command: "mvn -B -o test" });
        else {
          assert.match(outputs[3], /exit=0, timedOut=false/);
          assert.match(outputs[3], /Tests run: 6, Failures: 0, Errors: 0, Skipped: 0/);
          answer = reply("Six JUnit tests passed with Maven exit code 0.");
        }
      } else if (outputs.length === 0) answer = tool("read_file", { path: javaFile });
      else if (outputs.length === 1) {
        assert.ok(outputs[0].includes("!username.isBlank()"));
        answer = tool("write_file", { path: javaFile,
          content: goal === "Conflict B" ? validator.replace("{3,16}", "{5,20}") : validator });
      } else answer = reply("Validator updated.");
    }
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ choices: [{ message: answer, finish_reason: answer.tool_calls ? "tool_calls" : "stop" }] }));
  } catch (e) { res.writeHead(500); res.end(e.message); }
});
(async () => {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const processEnv = { ...process.env, SUPERAGENT_API_KEY: "local-fixture", SUPERAGENT_PROVIDER: "standard",
    SUPERAGENT_MODEL: "deterministic-fixture", SUPERAGENT_STATE_DIR: state,
    SUPERAGENT_BASE_URL: "http://127.0.0.1:" + server.address().port };
  const child = spawn("java", ["-jar", "target/superagent-java.jar", "--repo", repo], { env: processEnv });
  const done = once(child, "exit");
  let transcript = "";
  for (const stream of [child.stdout, child.stderr]) stream.setEncoding("utf8").on("data", s => { transcript += s; });
  const send = line => child.stdin.write(line + "\n");
  async function waitFor(regex, from = 0) {
    const deadline = Date.now() + 35000;
    while (Date.now() < deadline && child.exitCode === null) {
      const match = transcript.slice(from).match(regex);
      if (match) return match;
      await new Promise(resolve => setTimeout(resolve, 35));
    }
    throw new Error("Missing " + regex + "\n" + transcript);
  }
  const patchFor = id => {
    const dir = join(state, "deliveries", id);
    return join(dir, fs.readdirSync(dir)[0], "changes.patch");
  };
  try {
    await waitFor(/superagent>/);
    send("/plan Implement username validation and dependent tests");
    const [, id] = await waitFor(/(task_[a-f0-9]{32}) \| PLAN_READY/);
    send("/show " + id);
    await waitFor(/Verify validation/);
    send("/start " + id);
    const [, approval] = await waitFor(/命令待批准 (ap_[0-9a-f]{8})[\s\S]+?命令: mvn -B -o test/);
    send("/approve " + approval);
    await waitFor(new RegExp(id + " 总补丁待验收"));
    send("/diff " + id);
    await waitFor(/修改文件:/);
    const patch = patchFor(id);
    git(repo, "apply", "--check", patch);
    const review = join(run, "review");
    git(repo, "clone", "-q", "--no-hardlinks", "--", repo, review);
    git(review, "apply", patch);
    const maven = execFileSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", "mvn -B -o test"], {
      cwd: review, encoding: "utf8", timeout: 30000
    });
    assert.match(maven, /Tests run: 6, Failures: 0, Errors: 0, Skipped: 0/);
    fs.writeFileSync(join(run, "maven-review.log"), maven);
    send("/accept " + id);
    await waitFor(/已验收/);

    const conflictStart = transcript.length;
    send("/plan Reproduce conflict on the same validator line");
    const [, conflictId] = await waitFor(/(task_[a-f0-9]{32}) \| PLAN_READY/, conflictStart);
    send("/start " + conflictId);
    await waitFor(/合成暂停:[\s\S]+?UsernameValidator\.java/, conflictStart);
    const work = join(state, "work");
    const conflictWork = fs.readdirSync(work).find(name => name.startsWith(conflictId + "-assemble-"));
    assert.ok(conflictWork);
    fs.writeFileSync(join(work, conflictWork, javaFile), validator, "utf8");
    send("/resolve " + conflictId);
    await waitFor(/手工合成补丁待验收/, conflictStart);
    git(repo, "apply", "--check", patchFor(conflictId));
    assert.equal(git(repo, "status", "--porcelain"), "");
    assert.equal(git(repo, "rev-parse", "HEAD").trim(), base);
    send("/exit");
    child.stdin.end();
    const [exitCode] = await done;
    assert.equal(exitCode, 0, transcript);
    const approvedLog = fs.readdirSync(join(state, "logs"), { withFileTypes: true })
      .filter(entry => entry.isDirectory())
      .flatMap(entry => fs.readdirSync(join(state, "logs", entry.name))
        .filter(name => name.startsWith("command-")).map(name => join(state, "logs", entry.name, name)))[0];
    assert.ok(approvedLog);
    assert.match(fs.readFileSync(approvedLog, "utf8"), /Tests run: 6, Failures: 0, Errors: 0, Skipped: 0/);
    fs.writeFileSync(join(run, "evidence.json"), JSON.stringify({ modelHost: "127.0.0.1", model: "deterministic-fixture",
      taskId: id, baselineCommit: base, approvedMavenLog: approvedLog, patch,
      sha256: crypto.createHash("sha256").update(fs.readFileSync(patch)).digest("hex"),
      conflictId, conflictWorkspace: join(work, conflictWork), resolvedPatch: patchFor(conflictId),
      sourceUnchanged: true, mavenTestsPassed: 6 }, null, 2));
    console.log("PASS: " + run);
  } finally {
    fs.writeFileSync(join(run, "cli.log"), transcript);
    if (child.exitCode === null) child.kill();
    server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

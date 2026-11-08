package dev.superagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.superagent.model.Json;
import dev.superagent.task.Task;
import dev.superagent.task.Workspaces;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

final class GitHub implements Publisher.Remote {
    private final ServiceConfig config;
    private final Workspaces workspaces;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    GitHub(ServiceConfig config, Workspaces workspaces) {
        this.config = config;
        this.workspaces = workspaces;
    }

    @Override public void verifyRepository() throws Exception {
        if (request("GET", "", null, false).path("id").asLong() != config.repositoryId())
            throw new IllegalStateException("GitHub 仓库 ID 与部署配置不符");
    }

    @Override public String branchCommit(String branch) throws Exception {
        JsonNode ref = request("GET", "/git/ref/heads/" + branch, null, true);
        if (ref == null) return null;
        String sha = ref.path("object").path("sha").asText();
        if (!sha.matches("[0-9a-f]{40}")) throw new IllegalStateException("GitHub 返回了无效的提交 ID");
        return sha;
    }

    @Override public void push(Task task) throws Exception {
        workspaces.pushPublication(task, config.repository(), config.githubToken());
    }

    @Override public boolean baseContains(String ancestor, String head) throws Exception {
        if (!ancestor.matches("[0-9a-f]{40}") || !head.matches("[0-9a-f]{40}")) return false;
        JsonNode comparison = request("GET", "/compare/" + ancestor + "..." + head + "?per_page=1", null, true);
        return comparison != null && comparison.path("status").asText().equals("ahead")
                && comparison.path("merge_base_commit").path("sha").asText().equals(ancestor);
    }

    @Override public String existingPull(Task task) throws Exception {
        String owner = config.repository().split("/", 2)[0];
        var p = task.publication;
        JsonNode result = request("GET", "/pulls?state=all&head=" + encode(owner + ":" + p.branch)
                + "&base=" + encode(p.base) + "&per_page=10", null, false);
        if (!result.isArray()) throw new IllegalStateException("GitHub PR 查询结果无效");
        if (result.isEmpty()) return null;
        if (result.size() != 1) throw new IllegalStateException("存在多笔匹配的 PR，请人工核对");
        return checkedPull(result.get(0), task);
    }

    @Override public String openDraft(Task task) throws Exception {
        var p = task.publication;
        String issue = task.sourceThread.substring(task.sourceThread.lastIndexOf(':') + 1);
        JsonNode body = Json.object().put("title", "Super-agent: issue #" + issue)
                .put("head", p.branch).put("base", p.base).put("draft", true)
                .put("body", "Refs #" + issue + "\n\nReviewed patch SHA-256: `" + p.patchSha256 + "`");
        JsonNode created = request("POST", "/pulls", body, false);
        if (!created.path("draft").asBoolean()) throw new IllegalStateException("GitHub 未确认草稿状态，请人工核对");
        return checkedPull(created, task);
    }

    private String checkedPull(JsonNode node, Task task) {
        String url = node.path("html_url").asText();
        if (!node.path("head").path("sha").asText().equals(task.publication.commit)
                || !node.path("head").path("ref").asText().equals(task.publication.branch)
                || node.path("head").path("repo").path("id").asLong() != config.repositoryId()
                || node.path("base").path("repo").path("id").asLong() != config.repositoryId()
                || !node.path("base").path("ref").asText().equals(task.publication.base)
                || !url.toLowerCase(Locale.ROOT).matches("https://github\\.com/"
                        + java.util.regex.Pattern.quote(config.repository().toLowerCase(Locale.ROOT)) + "/pull/[1-9][0-9]*"))
            throw new IllegalStateException("远端 PR 与验收版本不符，请人工核对");
        return url;
    }

    private JsonNode request(String method, String path, JsonNode body, boolean missingAllowed) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + config.repository() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.githubToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.text(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (missingAllowed && response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("GitHub " + method + " 返回 HTTP " + response.statusCode());
        return Json.parse(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

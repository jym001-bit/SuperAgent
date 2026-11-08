package dev.superagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评测口径契约：传输层失败不能算进模型成功率。
 * 这条规则决定了报告数字能不能用来评价模型，改坏它比改坏一个判据更危险。
 */
class EvalInfraClassificationTest {

    private static EvalTaskResult result(String id, boolean success, String error, String status) {
        return new EvalTaskResult(id, id, success, status, error, List.of(), 1, 0, 10, "/tmp/artifacts", "",
                List.of(), List.of());
    }

    @Test void transportFailuresAreExcludedFromTheModelPassRate() {
        List<EvalTaskResult> tasks = List.of(
                result("a", true, "", "READY"),
                result("b", false,
                        "模型请求失败[api.deepseek.com/chat/completions]，先后尝试 3 次：连接失败，请求未送达（ConnectException: null）",
                        "FAILED"),
                result("c", false, "file_contains 未通过: 文件不存在", "READY"));

        EvalReport report = new EvalReport("real", "deepseek-flash", "t0", "t1", "/tmp/artifacts", tasks);
        assertEquals(3, report.total());
        assertEquals(1, report.passed());
        assertEquals(1, report.infraFailures(), "只有传输层失败被标为 infra");
        assertEquals(2, report.modelAttributable());
        assertEquals(0.5, report.modelPassRate(), 1e-9);
        assertEquals(1 / 3d, report.passRate(), 1e-9);

        String markdown = report.markdown();
        assertTrue(markdown.contains("传输层失败 1"), markdown);
        assertTrue(markdown.contains("失败(传输)"), "表格要区分传输层失败与模型失败");
        assertTrue(markdown.contains("模型口径通过率 50.0%"), markdown);
        assertEquals(0.5, dev.superagent.model.Json.parse(report.json()).path("modelPassRate").asDouble(), 1e-9);
    }

    @Test void pureModelFailuresKeepTheDenominator() {
        List<EvalTaskResult> tasks = List.of(
                result("a", true, "", "READY"),
                result("b", false, "no_changes 未通过: 工作区有改动", "READY"));
        EvalReport report = new EvalReport("real", "m", "t0", "t1", "/tmp", tasks);
        assertEquals(0, report.infraFailures());
        assertEquals(2, report.modelAttributable());
        assertEquals(0.5, report.modelPassRate(), 1e-9);
    }
}

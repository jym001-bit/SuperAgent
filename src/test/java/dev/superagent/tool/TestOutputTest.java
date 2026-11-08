package dev.superagent.tool;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 Maven surefire / Gradle / Cargo / Go / javac / Rust 输出样本验证结构化摘要。 */
class TestOutputTest {
    private static final String SUREFIRE_FAILURE = """
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running dev.example.UsernameValidatorTest
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.052 s <<< FAILURE! -- in dev.example.UsernameValidatorTest
[ERROR] dev.example.UsernameValidatorTest.rejectsBlankName -- Time elapsed: 0.012 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <false> but was: <true>
\tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
\tat dev.example.UsernameValidatorTest.rejectsBlankName(UsernameValidatorTest.java:42)
[INFO]
[INFO] Results:
[INFO]
[ERROR] Failures:
[ERROR]   UsernameValidatorTest.rejectsBlankName:42 expected: <false> but was: <true>
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 1
[INFO]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project username-validation: There are test failures.
[ERROR]
[INFO] BUILD FAILURE
""";

    @Test void realMavenSurefireFailureYieldsFailuresAndExitCode() {
        TestOutput.Summary summary = TestOutput.parse("mvn -B test", SUREFIRE_FAILURE, 1, false, 4000);
        assertEquals(1, summary.exitCode());
        assertEquals(7, summary.testsRun(), "应取 surefire 最后一行汇总计数");
        assertEquals(List.of("UsernameValidatorTest.rejectsBlankName"), summary.failures(),
                "失败用例名应去重且不带包名");
        assertTrue(summary.digest().contains("exit=1"), summary.digest());
        assertTrue(summary.digest().contains("失败 1 个"), summary.digest());
        assertTrue(summary.digest().contains("UsernameValidatorTest.rejectsBlankName"), summary.digest());
        assertTrue(summary.digest().startsWith("mvn -B test => "), summary.digest());
        assertTrue(summary.tail().contains("BUILD FAILURE"), summary.tail());
    }

    @Test void successDigestReportsPassingCount() {
        String output = """
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
""";
        TestOutput.Summary summary = TestOutput.parse("mvn -B test", output, 0, false, 400);
        assertEquals(0, summary.exitCode());
        assertEquals(12, summary.testsRun());
        assertEquals(List.of(), summary.failures());
        assertEquals("mvn -B test => exit=0, 测试 12 个全部通过", summary.digest());
        assertTrue(summary.tail().endsWith("BUILD SUCCESS\n"), summary.tail());
    }

    @Test void countsAreRecognizedForCommonRunners() {
        assertEquals(7, TestOutput.parse("mvn", "Tests run: 3, Failures: 0, Errors: 0\n[ERROR] Tests run: 7, Failures: 0, Errors: 0",
                0, false, 200).testsRun());
        assertEquals(5, TestOutput.parse("cargo", "test result: FAILED. 3 passed; 2 failed; 0 ignored", 1, false, 200).testsRun());
        assertEquals(4, TestOutput.parse("gradle", "4 tests completed, 1 failed", 1, false, 200).testsRun());
        assertEquals(9, TestOutput.parse("jest", "Tests:       1 failed, 9 total", 1, false, 200).testsRun());
    }

    @Test void bestEffortExtractionCoversGradleCargoGoJavacAndRust() {
        String output = """
> Task :test FAILED
UsernameValidatorTest > rejectsBlankName() FAILED
:test FAILED
test tests::rejects_blank ... FAILED
--- FAIL: TestRejectsBlank (0.00s)
src/test/java/dev/example/UsernameValidatorTest.java:12: error: cannot find symbol
error[E0308]: mismatched types
FAILED tests/test_username.py::test_rejects_blank - AssertionError
""";
        TestOutput.Summary summary = TestOutput.parse("gradle test", output, 1, false, 2000);
        List<String> failures = summary.failures();
        assertEquals(6, failures.size(), failures.toString());
        assertTrue(failures.contains("UsernameValidatorTest.rejectsBlankName"), failures.toString());
        assertTrue(failures.contains("tests::rejects_blank"), failures.toString());
        assertTrue(failures.contains("TestRejectsBlank"), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.endsWith("UsernameValidatorTest.java:12: error: cannot find symbol")), failures.toString());
        assertTrue(failures.contains("error[E0308]: mismatched types"), failures.toString());
        assertTrue(failures.contains("tests/test_username.py::test_rejects_blank"), failures.toString());
        assertFalse(failures.contains(":test"), "Gradle 任务路径不是失败用例");
        assertFalse(failures.contains("Task :test"), "Gradle 任务路径不是失败用例");
    }

    @Test void tailIsBoundedAndGarbageNeverThrows() {
        String longOutput = "x".repeat(1000);
        TestOutput.Summary clipped = TestOutput.parse("cmd", longOutput, 2, false, 100);
        assertEquals(100, clipped.tail().length());
        assertEquals(longOutput.substring(900), clipped.tail());

        String garbage = "\u0000\u0001bogus\r\n\t[ERROR] \uFFFD Tests run: garbage\n--- FAIL: (\n\tat";
        TestOutput.Summary broken = assertDoesNotThrow(() -> TestOutput.parse("cmd", garbage, 3, true, 5000));
        assertEquals(3, broken.exitCode());
        assertTrue(broken.timedOut());
        assertTrue(broken.digest().contains("超时"), broken.digest());

        TestOutput.Summary empty = assertDoesNotThrow(() -> TestOutput.parse(null, null, 0, false, 10));
        assertEquals("", empty.tail());
        assertTrue(empty.failures().isEmpty());
        assertEquals(0, empty.testsRun());
        assertEquals(0, TestOutput.parse("cmd", "abc", 0, false, 0).tail().length());
    }

    @Test void timeoutDigestIsExplicitAndFailureListIsCapped() {
        TestOutput.Summary timeout = TestOutput.parse("mvn -B test", "partial output", -1, true, 100);
        assertEquals(-1, timeout.exitCode());
        assertTrue(timeout.digest().contains("超时"), timeout.digest());
        assertTrue(timeout.digest().contains("mvn -B test"), timeout.digest());

        StringBuilder many = new StringBuilder("[ERROR] Tests run: 30, Failures: 25, Errors: 0, Skipped: 0\n");
        for (int i = 0; i < 25; i++) many.append("[ERROR]   FooTest.case").append(i).append(":").append(i + 1).append(" boom\n");
        TestOutput.Summary capped = TestOutput.parse("mvn -B test", many.toString(), 1, false, 1000);
        assertEquals(20, capped.failures().size(), "失败用例上限 20 条");
        assertTrue(capped.digest().contains("失败 25 个"), capped.digest());
        assertTrue(capped.digest().contains("等"), "超过 5 条只列前 5 条");
    }
}

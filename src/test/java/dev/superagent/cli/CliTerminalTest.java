package dev.superagent.cli;

import org.jline.reader.LineReaderBuilder;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.*;
import static org.junit.jupiter.api.Assertions.*;

class CliTerminalTest {
    @Test void fallbackReadsChineseUsingConsoleCharsetAndWritesUtf8() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String text = "它应该怎么改";
        var input = new ByteArrayInputStream((text + "\r\n").getBytes(gbk));
        var output = new ByteArrayOutputStream();
        try (var terminal = CliTerminal.streamTerminal("dumb", input, output, gbk)) {
            var reader = LineReaderBuilder.builder().terminal(terminal).build();
            assertEquals(text, reader.readLine("superagent> "));
            reader.printAbove("中文回复");
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("中文回复"));
    }
}

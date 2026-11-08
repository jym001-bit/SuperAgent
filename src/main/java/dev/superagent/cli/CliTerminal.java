package dev.superagent.cli;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CliTerminal {
    private CliTerminal() {}

    static Terminal open() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build();
        Console console = System.console();
        if (terminal instanceof DumbTerminal && console != null
                && !console.charset().equals(StandardCharsets.UTF_8)) {
            // Windows stream fallback can receive GBK even when the output channel consumes UTF-8.
            try (terminal) {
                Terminal fallback = streamTerminal(terminal.getType(), System.in, System.out, console.charset());
                fallback.setSize(terminal.getSize());
                return fallback;
            }
        }
        return terminal;
    }

    static Terminal streamTerminal(String type, InputStream input, OutputStream output, Charset inputCharset)
            throws IOException {
        return new DumbTerminal("superagent", type, input, output, inputCharset) {
            private final PrintWriter utf8Writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);

            @Override public PrintWriter writer() { return utf8Writer; }

            @Override protected void doClose() throws IOException {
                utf8Writer.flush();
                reader().shutdown();
                super.doClose();
            }
        };
    }
}

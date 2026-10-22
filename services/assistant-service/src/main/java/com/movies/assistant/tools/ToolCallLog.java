package com.movies.assistant.tools;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * The tool calls of one answer: the arguments the model sent and the result it got back, which the
 * evals score against (assistant.chat.stream-tool-calls). The controller puts one in the
 * {@link ToolContext} under {@link #KEY}; {@link #recording} wraps the tools to fill it.
 */
public class ToolCallLog {

    /** ToolContext key holding the answer's log. Without one, nothing is recorded. */
    public static final String KEY = "toolCallLog";

    /**
     * One call. {@code arguments} and {@code result} are the JSON the model sent and got; a failed
     * call's result is the error message the model got instead.
     */
    public record Entry(String name, String arguments, String result, boolean failed) {
    }

    // The model can ask for several tools at once, and they may run in parallel.
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public static ToolCallback[] recording(ToolCallback[] tools) {
        ToolCallback[] wrapped = new ToolCallback[tools.length];
        for (int i = 0; i < tools.length; i++) {
            wrapped[i] = new Recording(tools[i]);
        }
        return wrapped;
    }

    private record Recording(ToolCallback delegate) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String arguments) {
            return call(arguments, null);
        }

        @Override
        public String call(String arguments, ToolContext toolContext) {
            ToolCallLog log = toolContext != null && toolContext.getContext().get(KEY) instanceof ToolCallLog l ? l : null;
            String name = delegate.getToolDefinition().name();
            try {
                String result = delegate.call(arguments, toolContext);
                if (log != null) {
                    log.entries.add(new Entry(name, arguments, result, false));
                }
                return result;
            } catch (RuntimeException ex) {
                // Spring AI sends this message to the model as the result (see ToolCallException).
                if (log != null) {
                    log.entries.add(new Entry(name, arguments, ex.getMessage(), true));
                }
                throw ex;
            }
        }
    }
}

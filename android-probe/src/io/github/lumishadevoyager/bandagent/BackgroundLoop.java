package io.github.lumishadevoyager.bandagent;

import java.util.function.Consumer;

/** A transient failure must not cancel ScheduledExecutor's next execution. */
public final class BackgroundLoop {
    public static void run(Runnable task, Consumer<RuntimeException> failure) {
        try { task.run(); } catch (RuntimeException error) { failure.accept(error); }
    }
}

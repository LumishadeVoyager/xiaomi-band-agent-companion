import io.github.lumishadevoyager.bandagent.BackgroundLoop;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BackgroundLoopTest {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
        AtomicInteger calls=new AtomicInteger(), failures=new AtomicInteger();
        CountDownLatch resumed=new CountDownLatch(2);
        try {
            ScheduledFuture<?> future=scheduler.scheduleWithFixedDelay(() -> BackgroundLoop.run(() -> {
                if(calls.incrementAndGet()==1) throw new IllegalStateException("injected SDK failure");
                resumed.countDown();
            },error -> failures.incrementAndGet()),0,5,TimeUnit.MILLISECONDS);
            if(!resumed.await(3,TimeUnit.SECONDS)||future.isDone()||failures.get()!=1)
                throw new AssertionError("periodic sync did not survive failure");
            future.cancel(false);
        } finally { scheduler.shutdownNow(); }
        System.out.println("Background loop: real scheduled execution survives injected runtime failure");
    }
}

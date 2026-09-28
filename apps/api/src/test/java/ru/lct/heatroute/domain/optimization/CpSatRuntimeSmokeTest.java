package ru.lct.heatroute.domain.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolverStatus;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Загружает pinned native-библиотеку и проверяет solve, повторную загрузку и stopSearch. */
class CpSatRuntimeSmokeTest {
    private final CpSatRuntime runtime = new CpSatRuntime();

    @Test
    void solvesKnownOptimumRepeatedly() {
        CpSatRuntime.Capability first = runtime.verifyCapability();
        CpSatRuntime.Capability second = runtime.verifyCapability();

        assertThat(first.getVersion()).isEqualTo("9.15.6755");
        assertThat(first.getStatus()).isEqualTo(CpSolverStatus.OPTIMAL.name());
        assertThat(first.getObjective()).isEqualTo(3.0);
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
        assertThat(second.getObjective()).isEqualTo(first.getObjective());
    }

    @Test
    void stopBeforeSolveIsLatchedWithoutEnteringNativeSearch() {
        try (CpSatRuntime.Session session = runtime.newSession(runtime.newModel(),
                CpSatRuntime.Settings.deterministic(30.0, 2026))) {
            session.requestStop();

            assertThatThrownBy(session::solve).isInstanceOf(CancellationException.class);
            assertThat(session.isFinished()).isTrue();
        }
    }

    @Test
    void asynchronousStopTerminatesAnActiveNativeSearch() throws Exception {
        CpModel model = runtime.newModel();
        for (int index = 0; index < 80; index++) model.newBoolVar("choice-" + index);
        CpSatRuntime.Session session = runtime.newSession(model,
                CpSatRuntime.Settings.enumerating(30.0, 2026));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CpSolverStatus> future = executor.submit(session::solve);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (!session.isSolving() && System.nanoTime() < deadline) Thread.yield();
            assertThat(session.isSolving()).isTrue();

            session.requestStop();

            assertThatThrownBy(() -> future.get(5L, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CancellationException.class);
            assertThat(session.awaitFinished(1L, TimeUnit.SECONDS)).isTrue();
        } finally {
            session.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2L, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptingTheSolvingThreadStopsNativeSearch() throws Exception {
        CpModel model = runtime.newModel();
        for (int index = 0; index < 80; index++) model.newBoolVar("interrupt-choice-" + index);
        CpSatRuntime.Session session = runtime.newSession(model,
                CpSatRuntime.Settings.enumerating(30.0, 2026));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread solving = new Thread(() -> {
            try {
                session.solve();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "cp-sat-interrupt-test");
        try {
            solving.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (!session.isSolving() && System.nanoTime() < deadline) Thread.yield();
            assertThat(session.isSolving()).isTrue();

            solving.interrupt();

            assertThat(session.awaitFinished(5L, TimeUnit.SECONDS)).isTrue();
            solving.join(TimeUnit.SECONDS.toMillis(1L));
            assertThat(solving.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
        } finally {
            session.close();
            solving.interrupt();
        }
    }
}

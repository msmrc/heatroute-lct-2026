package ru.lct.heatroute.domain.optimization;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.SatParameters;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Изолирует загрузку native CP-SAT и жизненный цикл одного синхронного solve.
 * Отмена защёлкивается и повторяет {@code stopSearch}, не удерживая monitor во время native-вызова.
 */
public final class CpSatRuntime {
    public static final String VERSION = "9.15.6755";
    private static final long STOP_RETRY_INTERVAL_MS = 5L;
    private static final long STOP_RETRY_BUDGET_MS = 10_000L;
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();
    private static volatile boolean nativeLibrariesLoaded;

    /** Создаёт модель только после успешной загрузки JNI; прямой {@code new CpModel()} запрещён. */
    public CpModel newModel() {
        ensureNativeLibrariesLoaded();
        return new CpModel();
    }

    /** Создаёт одноразовую сессию с детерминированными параметрами поиска. */
    public Session newSession(CpModel model, Settings settings) {
        ensureNativeLibrariesLoaded();
        return new Session(model, settings);
    }

    /** Выполняет маленькую задачу с известным оптимумом для readiness и release-проверок. */
    public Capability verifyCapability() {
        CpModel model = newModel();
        BoolVar x = model.newBoolVar("x");
        BoolVar y = model.newBoolVar("y");
        model.addLessOrEqual(LinearExpr.newBuilder().add(x).add(y), 1L);
        model.maximize(LinearExpr.newBuilder().addTerm(x, 3L).addTerm(y, 2L));
        try (Session session = newSession(model, Settings.deterministic(5.0, 2026))) {
            CpSolverStatus status = session.solve();
            if (status != CpSolverStatus.OPTIMAL || session.value(x) != 1L || session.value(y) != 0L
                    || Math.abs(session.objectiveValue() - 3.0) > 1e-9) {
                throw new IllegalStateException("CP-SAT capability result is inconsistent");
            }
            return new Capability(VERSION, status.name(), session.objectiveValue());
        }
    }

    private static void ensureNativeLibrariesLoaded() {
        if (nativeLibrariesLoaded) return;
        synchronized (CpSatRuntime.class) {
            if (nativeLibrariesLoaded) return;
            Loader.loadNativeLibraries();
            nativeLibrariesLoaded = true;
        }
    }

    /** Ограниченные параметры solve; один worker сохраняет воспроизводимость первого этапа. */
    public static final class Settings {
        private final double maxTimeSeconds;
        private final int randomSeed;
        private final boolean enumerateAllSolutions;

        private Settings(double maxTimeSeconds, int randomSeed, boolean enumerateAllSolutions) {
            if (!Double.isFinite(maxTimeSeconds) || maxTimeSeconds <= 0.0 || randomSeed < 0) {
                throw new IllegalArgumentException("Finite positive time and non-negative seed required");
            }
            this.maxTimeSeconds = maxTimeSeconds;
            this.randomSeed = randomSeed;
            this.enumerateAllSolutions = enumerateAllSolutions;
        }

        public static Settings deterministic(double maxTimeSeconds, int randomSeed) {
            return new Settings(maxTimeSeconds, randomSeed, false);
        }

        public static Settings enumerating(double maxTimeSeconds, int randomSeed) {
            return new Settings(maxTimeSeconds, randomSeed, true);
        }

        private void apply(SatParameters.Builder parameters) {
            parameters.setMaxTimeInSeconds(maxTimeSeconds)
                    .setRandomSeed(randomSeed)
                    .setNumSearchWorkers(1)
                    .setEnumerateAllSolutions(enumerateAllSolutions);
        }
    }

    /**
     * Одноразовая solve-сессия. {@link #requestStop()} безопасен до, во время и сразу после
     * регистрации native handle; после отмены {@link #solve()} всегда сообщает cancellation.
     */
    public static final class Session implements AutoCloseable {
        private final CpModel model;
        private final CpSolver solver = new CpSolver();
        private final Object cancellationGate = new Object();
        private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean stopWatchdogStarted = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile CpSolverStatus status;
        private volatile Thread stopWatchdog;
        private volatile Thread interruptMonitor;

        private Session(CpModel model, Settings settings) {
            this.model = Objects.requireNonNull(model, "model");
            Objects.requireNonNull(settings, "settings").apply(solver.getParameters());
        }

        public CpSolverStatus solve() {
            return solveNative(() -> solver.solve(model));
        }

        /** Останавливает native search на первом допустимом incumbent, не помечая это отменой. */
        public CpSolverStatus solveFirstFeasible() {
            CpSolverSolutionCallback callback = new CpSolverSolutionCallback() {
                @Override
                public void onSolutionCallback() {
                    stopSearch();
                }
            };
            try {
                return solveNative(() -> solver.solve(model, callback));
            } finally {
                callback.delete();
            }
        }

        private CpSolverStatus solveNative(NativeSolve nativeSolve) {
            synchronized (cancellationGate) {
                if (!state.compareAndSet(State.CREATED, State.SOLVING)) {
                    throw new IllegalStateException("CP-SAT session can be solved only once");
                }
                if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                    stopRequested.set(true);
                    state.set(State.FINISHED);
                    finished.countDown();
                    throw new CancellationException("CP-SAT solve cancelled before native search");
                }
            }
            Thread solvingThread = Thread.currentThread();
            Thread monitor = new Thread(() -> stopWhenInterrupted(solvingThread),
                    "heatroute-cp-sat-interrupt-" + SESSION_SEQUENCE.incrementAndGet());
            monitor.setDaemon(true);
            interruptMonitor = monitor;
            monitor.start();
            try {
                status = nativeSolve.run();
                synchronized (cancellationGate) {
                    if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                        stopRequested.set(true);
                        throw new CancellationException("CP-SAT solve cancelled");
                    }
                    state.set(State.FINISHED);
                }
                return status;
            } finally {
                synchronized (cancellationGate) {
                    if (state.get() == State.SOLVING) state.set(State.FINISHED);
                }
                finished.countDown();
                Thread watchdog = stopWatchdog;
                if (watchdog != null) watchdog.interrupt();
                Thread activeMonitor = interruptMonitor;
                if (activeMonitor != null) activeMonitor.interrupt();
            }
        }

        private void stopWhenInterrupted(Thread solvingThread) {
            try {
                while (finished.getCount() > 0L) {
                    if (solvingThread.isInterrupted()) {
                        requestStop();
                        return;
                    }
                    if (finished.await(STOP_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)) return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        /** Защёлкивает отмену и асинхронно достаёт native solve даже при гонке его регистрации. */
        public void requestStop() {
            boolean startWatchdog;
            synchronized (cancellationGate) {
                stopRequested.set(true);
                solver.stopSearch();
                startWatchdog = state.get() == State.SOLVING
                        && stopWatchdogStarted.compareAndSet(false, true);
            }
            if (startWatchdog) {
                Thread watchdog = new Thread(this::repeatStopUntilFinished,
                        "heatroute-cp-sat-stop-" + SESSION_SEQUENCE.incrementAndGet());
                watchdog.setDaemon(true);
                stopWatchdog = watchdog;
                watchdog.start();
            }
        }

        private void repeatStopUntilFinished() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_RETRY_BUDGET_MS);
            try {
                while (finished.getCount() > 0L && System.nanoTime() < deadline) {
                    solver.stopSearch();
                    if (finished.await(STOP_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)) return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        public boolean isSolving() {
            return state.get() == State.SOLVING;
        }

        public boolean isFinished() {
            return state.get() == State.FINISHED || state.get() == State.CLOSED;
        }

        public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        public long value(LinearArgument expression) {
            ensureSolutionAvailable();
            return solver.value(expression);
        }

        public long value(BoolVar variable) {
            ensureSolutionAvailable();
            return solver.value(variable);
        }

        public double objectiveValue() {
            ensureSolutionAvailable();
            return solver.objectiveValue();
        }

        private void ensureSolutionAvailable() {
            if (state.get() != State.FINISHED || status != CpSolverStatus.FEASIBLE
                    && status != CpSolverStatus.OPTIMAL) {
                throw new IllegalStateException("CP-SAT solution is not available");
            }
        }

        @Override
        public void close() {
            boolean stop;
            synchronized (cancellationGate) {
                State current = state.get();
                stop = current == State.SOLVING;
                if (state.compareAndSet(State.CREATED, State.CLOSED)) finished.countDown();
                else if (current == State.FINISHED) state.compareAndSet(State.FINISHED, State.CLOSED);
            }
            if (stop) requestStop();
        }

        @FunctionalInterface
        private interface NativeSolve {
            CpSolverStatus run();
        }
    }

    /** Минимальные диагностические данные без внутреннего native log. */
    public static final class Capability {
        private final String version;
        private final String status;
        private final double objective;

        private Capability(String version, String status, double objective) {
            this.version = version;
            this.status = status;
            this.objective = objective;
        }

        public String getVersion() { return version; }
        public String getStatus() { return status; }
        public double getObjective() { return objective; }
    }

    private enum State { CREATED, SOLVING, FINISHED, CLOSED }
}

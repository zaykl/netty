/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import io.netty.monitor.EventRateMonitor;
import io.netty.monitor.MonitorName;
import io.netty.monitor.MonitorRegistry;
import io.netty.monitor.ValueDistributionMonitor;
import io.netty.util.internal.PlatformDependent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
        InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector =
            new ResourceLeakDetector<HashedWheelTimer>(
                    HashedWheelTimer.class, 1, Runtime.getRuntime().availableProcessors() * 4);

    private static final MonitorName TIMEOUT_EXPIRATION_TIME_DEVIATION_MN = new MonitorName(HashedWheelTimer.class,
            "timeout-expiration-time-deviation");
    private static final MonitorName TIMEOUTS_PER_SECOND_MN = new MonitorName(HashedWheelTimer.class,
            "timeouts-per-second");

    private final ResourceLeak leak = leakDetector.open(this);
    private final Worker worker = new Worker();
    final Thread workerThread;
    final AtomicInteger workerState = new AtomicInteger(); // 0 - init, 1 - started, 2 - shut down

    private final long roundDuration;
    final long tickDuration;
    final Set<HashedWheelTimeout>[] wheel;
    final int mask;
    final ReadWriteLock lock = new ReentrantReadWriteLock();
    volatile int wheelCursor;

    // Monitoring this instance
    final ValueDistributionMonitor timeoutExpirationTimeDeviation;
    final EventRateMonitor timeoutsPerSecond;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is <= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is <= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is <= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is <= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, MonitorRegistry.NOOP);
    }

    /**
     * Creates a new timer with {@link io.netty.monitor monitoring support}
     * enabled. The new timer instance will monitor
     * <ul>
     * <li>
     * the distribution of deviation between scheduled and actual
     * {@link Timeout timeout} execution, i.e. how accurate this timer is; and
     * </li>
     * <li>
     * the rate of {@link Timeout timeout} executions per second.
     * </li>
     * </ul>
     *
     * @param threadFactory   a {@link ThreadFactory} that creates a
     *                        background {@link Thread} which is dedicated to
     *                        {@link TimerTask} execution.
     * @param tickDuration    the duration between tick
     * @param unit            the time unit of the {@code tickDuration}
     * @param ticksPerWheel   the size of the wheel
     * @param monitorRegistry the {@link MonitorRegistry} to use
     * @throws NullPointerException
     *                        if either of {@code threadFactory} and {@code unit}
     *                        is {@code null}
     * @throws IllegalArgumentException
     *                        if either of {@code tickDuration} and {@code ticksPerWheel} is <= 0
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel,
            MonitorRegistry monitorRegistry) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, monitorRegistry,
             TIMEOUT_EXPIRATION_TIME_DEVIATION_MN, TIMEOUTS_PER_SECOND_MN);
    }

    /**
     * Creates a new timer with {@link io.netty.monitor monitoring support}
     * enabled. The new timer instance will monitor
     * <ul>
     * <li>
     * the distribution of deviation between scheduled and actual
     * {@link Timeout timeout} execution, i.e. how accurate this timer is; and
     * </li>
     * <li>
     * the rate of {@link Timeout timeout} executions per second.
     * </li>
     * </ul>
     *
     * @param threadFactory   a {@link ThreadFactory} that creates a
     *                        background {@link Thread} which is dedicated to
     *                        {@link TimerTask} execution.
     * @param tickDuration    the duration between tick
     * @param unit            the time unit of the {@code tickDuration}
     * @param ticksPerWheel   the size of the wheel
     * @param monitorRegistry the {@link MonitorRegistry} to use
     * @param timeoutExpirationTimeDeviationMonitorName
     *                        the {@link MonitorName name} to use for the
     *                        {@code Monitor} that tracks the distribution of
     *                        deviation between scheduled and actual timeout
     *                        execution
     * @param timeoutsPerSecondMonitorName
     *                        the {@link MonitorName name} to use for the
     *                        {@code Monitor} that tracks the rate of timeout
     *                        executions per second
     * @throws NullPointerException
     *                        if either of {@code threadFactory}, {@code unit},
     *                        {@code monitorRegistry},
     *                        {@code timeoutExpirationTimeDeviationMonitorName} and
     *                        {@code timeoutsPerSecondMonitorName} is {@code null}
     * @throws IllegalArgumentException
     *                        if either of {@code tickDuration} and {@code ticksPerWheel} is <= 0
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel,
            MonitorRegistry monitorRegistry, MonitorName timeoutExpirationTimeDeviationMonitorName,
            MonitorName timeoutsPerSecondMonitorName) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (monitorRegistry == null) {
            throw new NullPointerException("monitorRegistry");
        }
        if (timeoutExpirationTimeDeviationMonitorName == null) {
            throw new NullPointerException("timeoutExpirationTimeDeviationMonitorName");
        }
        if (timeoutsPerSecondMonitorName == null) {
            throw new NullPointerException("timeoutsPerSecondMonitorName");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // Convert tickDuration to milliseconds.
        this.tickDuration = tickDuration = unit.toMillis(tickDuration);

        // Prevent overflow.
        if (tickDuration == Long.MAX_VALUE || tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException("tickDuration is too long: " + tickDuration + ' ' + unit);
        }

        roundDuration = tickDuration * wheel.length;

        workerThread = threadFactory.newThread(worker);

        timeoutExpirationTimeDeviation =
                monitorRegistry.newValueDistributionMonitor(timeoutExpirationTimeDeviationMonitorName);
        timeoutsPerSecond =
                monitorRegistry.newEventRateMonitor(timeoutsPerSecondMonitorName, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static Set<HashedWheelTimeout>[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        Set<HashedWheelTimeout>[] wheel = new Set[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = Collections.newSetFromMap(
                    new ConcurrentHashMap<HashedWheelTimeout, Boolean>(16, 0.95f, 4));
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        switch (workerState.get()) {
        case 0:
            if (workerState.compareAndSet(0, 1)) {
                workerThread.start();
            }
            break;
        case 1:
            break;
        case 2:
            throw new IllegalStateException("cannot be started once stopped");
        default:
            throw new Error();
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                    ".stop() cannot be called from " +
                    TimerTask.class.getSimpleName());
        }

        if (!workerState.compareAndSet(1, 2)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            workerState.set(2);
            return Collections.emptySet();
        }

        boolean interrupted = false;
        while (workerThread.isAlive()) {
            workerThread.interrupt();
            try {
                workerThread.join(100);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        leak.close();

        Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();
        for (Set<HashedWheelTimeout> bucket: wheel) {
            unprocessedTimeouts.addAll(bucket);
            bucket.clear();
        }

        return Collections.unmodifiableSet(unprocessedTimeouts);
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        final long currentTime = System.currentTimeMillis();

        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        start();

        delay = unit.toMillis(delay);
        HashedWheelTimeout timeout = new HashedWheelTimeout(task, currentTime + delay);
        scheduleTimeout(timeout, delay);
        return timeout;
    }

    void scheduleTimeout(HashedWheelTimeout timeout, long delay) {
        // delay must be equal to or greater than tickDuration so that the
        // worker thread never misses the timeout.
        if (delay < tickDuration) {
            delay = tickDuration;
        }

        // Prepare the required parameters to schedule the timeout object.
        final long lastRoundDelay = delay % roundDuration;
        final long lastTickDelay = delay % tickDuration;
        final long relativeIndex =
            lastRoundDelay / tickDuration + (lastTickDelay != 0? 1 : 0);

        final long remainingRounds =
            delay / roundDuration - (delay % roundDuration == 0? 1 : 0);

        // Add the timeout to the wheel.
        lock.readLock().lock();
        try {
            int stopIndex = (int) (wheelCursor + relativeIndex & mask);
            timeout.stopIndex = stopIndex;
            timeout.remainingRounds = remainingRounds;

            wheel[stopIndex].add(timeout);
        } finally {
            lock.readLock().unlock();
        }
    }

    private final class Worker implements Runnable {

        private long startTime;
        private long tick;

        Worker() {
        }

        @Override
        public void run() {
            List<HashedWheelTimeout> expiredTimeouts =
                new ArrayList<HashedWheelTimeout>();

            startTime = System.currentTimeMillis();
            tick = 1;

            while (workerState.get() == 1) {
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    fetchExpiredTimeouts(expiredTimeouts, deadline);
                    notifyExpiredTimeouts(expiredTimeouts);
                }
            }
        }

        private void fetchExpiredTimeouts(
                List<HashedWheelTimeout> expiredTimeouts, long deadline) {

            // Find the expired timeouts and decrease the round counter
            // if necessary.  Note that we don't send the notification
            // immediately to make sure the listeners are called without
            // an exclusive lock.
            lock.writeLock().lock();
            try {
                int newWheelCursor = wheelCursor = wheelCursor + 1 & mask;
                fetchExpiredTimeouts(expiredTimeouts, wheel[newWheelCursor].iterator(), deadline);
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void fetchExpiredTimeouts(
                List<HashedWheelTimeout> expiredTimeouts,
                Iterator<HashedWheelTimeout> i, long deadline) {

            List<HashedWheelTimeout> slipped = null;
            while (i.hasNext()) {
                HashedWheelTimeout timeout = i.next();
                if (timeout.remainingRounds <= 0) {
                    i.remove();
                    if (timeout.deadline <= deadline) {
                        expiredTimeouts.add(timeout);
                    } else {
                        // Handle the case where the timeout is put into a wrong
                        // place, usually one tick earlier.  For now, just add
                        // it to a temporary list - we will reschedule it in a
                        // separate loop.
                        if (slipped == null) {
                            slipped = new ArrayList<HashedWheelTimeout>();
                        }
                        slipped.add(timeout);
                    }
                } else {
                    timeout.remainingRounds --;
                }
            }

            // Reschedule the slipped timeouts.
            if (slipped != null) {
                for (HashedWheelTimeout timeout: slipped) {
                    scheduleTimeout(timeout, timeout.deadline - deadline);
                }
            }
        }

        private void notifyExpiredTimeouts(
                List<HashedWheelTimeout> expiredTimeouts) {
            // Notify the expired timeouts.
            for (int i = expiredTimeouts.size() - 1; i >= 0; i --) {
                expiredTimeouts.get(i).expire();
            }

            // Clean up the temporary list.
            expiredTimeouts.clear();
        }

        private long waitForNextTick() {
            long deadline = startTime + tickDuration * tick;

            for (;;) {
                final long currentTime = System.currentTimeMillis();
                long sleepTime = tickDuration * tick - (currentTime - startTime);

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) {
                    sleepTime = sleepTime / 10 * 10;
                }

                if (sleepTime <= 0) {
                    break;
                }

                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    if (workerState.get() != 1) {
                        return -1;
                    }
                }
            }

            // Increase the tick.
            tick ++;
            return deadline;
        }
    }

    private final class HashedWheelTimeout implements Timeout {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        private final TimerTask task;
        final long deadline;
        volatile int stopIndex;
        volatile long remainingRounds;
        private final AtomicInteger state = new AtomicInteger(ST_INIT);

        HashedWheelTimeout(TimerTask task, long deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return HashedWheelTimer.this;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            if (!state.compareAndSet(ST_INIT, ST_CANCELLED)) {
                return false;
            }

            wheel[stopIndex].remove(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return state.get() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state.get() != ST_INIT;
        }

        public void expire() {
            if (!state.compareAndSet(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                timeoutsPerSecond.event();
                timeoutExpirationTimeDeviation.update(System.currentTimeMillis() - deadline);
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "An exception was thrown by " +
                            TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            long currentTime = System.currentTimeMillis();
            long remaining = deadline - currentTime;

            StringBuilder buf = new StringBuilder(192);
            buf.append(getClass().getSimpleName());
            buf.append('(');

            buf.append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining);
                buf.append(" ms later, ");
            } else if (remaining < 0) {
                buf.append(-remaining);
                buf.append(" ms ago, ");
            } else {
                buf.append("now, ");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(')').toString();
        }
    }
}

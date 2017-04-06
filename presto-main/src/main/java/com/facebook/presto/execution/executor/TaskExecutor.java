/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution.executor;

import com.facebook.presto.execution.SplitRunner;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskManagerConfig;
import com.facebook.presto.spi.PrestoException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.ComparisonChain;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.SetThreadName;
import io.airlift.concurrent.ThreadPoolExecutorMBean;
import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import io.airlift.stats.TimeDistribution;
import io.airlift.stats.TimeStat;
import io.airlift.units.Duration;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.DoubleSupplier;

import static com.facebook.presto.execution.executor.PrioritizedSplitRunner.calculatePriorityLevel;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.newConcurrentHashSet;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.concurrent.Threads.threadsNamed;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@ThreadSafe
public class TaskExecutor
{
    private static final Logger log = Logger.get(TaskExecutor.class);

    // each task is guaranteed a minimum number of splits
    private static final int GUARANTEED_SPLITS_PER_TASK = 3;

    // print out split call stack if it has been running for a certain amount of time
    private static final Duration LONG_SPLIT_WARNING_THRESHOLD = new Duration(1000, TimeUnit.SECONDS);

    private static final AtomicLong NEXT_RUNNER_ID = new AtomicLong();

    private final ExecutorService executor;
    private final ThreadPoolExecutorMBean executorMBean;

    private final int runnerThreads;
    private final int minimumNumberOfDrivers;

    private final Ticker ticker;

    private final ScheduledExecutorService splitMonitorExecutor = newSingleThreadScheduledExecutor(daemonThreadsNamed("TaskExecutor"));
    private final SortedSet<RunningSplitInfo> runningSplitInfos = new ConcurrentSkipListSet<>();

    @GuardedBy("this")
    private final List<TaskHandle> tasks;

    /**
     * All splits registered with the task executor.
     */
    @GuardedBy("this")
    private final Set<PrioritizedSplitRunner> allSplits = new HashSet<>();

    /**
     * Intermediate splits (i.e. splits that should not be queued).
     */
    @GuardedBy("this")
    private final Set<PrioritizedSplitRunner> intermediateSplits = new HashSet<>();

    /**
     * Splits waiting for a runner thread.
     */
    private final PriorityBlockingQueue<PrioritizedSplitRunner> waitingSplits;

    /**
     * Splits running on a thread.
     */
    private final Set<PrioritizedSplitRunner> runningSplits = newConcurrentHashSet();

    /**
     * Splits blocked by the driver.
     */
    private final Map<PrioritizedSplitRunner, Future<?>> blockedSplits = new ConcurrentHashMap<>();

    private final AtomicLongArray completedTasksPerLevel = new AtomicLongArray(5);
    private final AtomicLongArray completedSplitsPerLevel = new AtomicLongArray(5);

    private final CounterStat[] selectedLevelCounters = new CounterStat[5];

    private final TimeStat splitQueuedTime = new TimeStat(NANOSECONDS);
    private final TimeStat splitWallTime = new TimeStat(NANOSECONDS);

    private final TimeDistribution leafSplitWallTime = new TimeDistribution(MICROSECONDS);
    private final TimeDistribution intermediateSplitWallTime = new TimeDistribution(MICROSECONDS);

    private final TimeDistribution leafSplitScheduledTime = new TimeDistribution(MICROSECONDS);
    private final TimeDistribution intermediateSplitScheduledTime = new TimeDistribution(MICROSECONDS);

    private final TimeDistribution leafSplitWaitTime = new TimeDistribution(MICROSECONDS);
    private final TimeDistribution intermediateSplitWaitTime = new TimeDistribution(MICROSECONDS);

    // shared between SplitRunners
    private final CounterStat globalCpuTimeMicros = new CounterStat();
    private final CounterStat globalScheduledTimeMicros = new CounterStat();

    private final TimeStat blockedQuantaWallTime = new TimeStat(MICROSECONDS);
    private final TimeStat unblockedQuantaWallTime = new TimeStat(MICROSECONDS);

    private volatile boolean closed;

    @Inject
    public TaskExecutor(TaskManagerConfig config)
    {
        this(requireNonNull(config, "config is null").getMaxWorkerThreads(), config.getMinDrivers());
    }

    public TaskExecutor(int runnerThreads, int minDrivers)
    {
        this(runnerThreads, minDrivers, Ticker.systemTicker());
    }

    @VisibleForTesting
    public TaskExecutor(int runnerThreads, int minDrivers, Ticker ticker)
    {
        checkArgument(runnerThreads > 0, "runnerThreads must be at least 1");

        // we manage thread pool size directly, so create an unlimited pool
        this.executor = newCachedThreadPool(threadsNamed("task-processor-%s"));
        this.executorMBean = new ThreadPoolExecutorMBean((ThreadPoolExecutor) executor);
        this.runnerThreads = runnerThreads;

        this.ticker = requireNonNull(ticker, "ticker is null");

        this.minimumNumberOfDrivers = minDrivers;
        this.waitingSplits = new PriorityBlockingQueue<>(Runtime.getRuntime().availableProcessors() * 10);
        this.tasks = new LinkedList<>();

        for (int i = 0; i < 5; i++) {
            selectedLevelCounters[i] = new CounterStat();
        }
    }

    @PostConstruct
    public synchronized void start()
    {
        checkState(!closed, "TaskExecutor is closed");
        for (int i = 0; i < runnerThreads; i++) {
            addRunnerThread();
        }
        splitMonitorExecutor.scheduleWithFixedDelay(this::monitorActiveSplits, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public synchronized void stop()
    {
        closed = true;
        executor.shutdownNow();
        splitMonitorExecutor.shutdownNow();
    }

    @Override
    public synchronized String toString()
    {
        return toStringHelper(this)
                .add("runnerThreads", runnerThreads)
                .add("allSplits", allSplits.size())
                .add("intermediateSplits", intermediateSplits.size())
                .add("waitingSplits", waitingSplits.size())
                .add("runningSplits", runningSplits.size())
                .add("blockedSplits", blockedSplits.size())
                .toString();
    }

    private synchronized void addRunnerThread()
    {
        try {
            executor.execute(new TaskRunner());
        }
        catch (RejectedExecutionException ignored) {
        }
    }

    public synchronized TaskHandle addTask(TaskId taskId, DoubleSupplier utilizationSupplier, int initialSplitConcurrency, Duration splitConcurrencyAdjustFrequency)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(utilizationSupplier, "utilizationSupplier is null");
        TaskHandle taskHandle = new TaskHandle(taskId, utilizationSupplier, initialSplitConcurrency, splitConcurrencyAdjustFrequency);
        tasks.add(taskHandle);
        return taskHandle;
    }

    public void removeTask(TaskHandle taskHandle)
    {
        List<PrioritizedSplitRunner> splits;
        synchronized (this) {
            tasks.remove(taskHandle);
            splits = taskHandle.destroy();

            // stop tracking splits (especially blocked splits which may never unblock)
            allSplits.removeAll(splits);
            intermediateSplits.removeAll(splits);
            blockedSplits.keySet().removeAll(splits);
            waitingSplits.removeAll(splits);
        }

        // call destroy outside of synchronized block as it is expensive and doesn't need a lock on the task executor
        for (PrioritizedSplitRunner split : splits) {
            split.destroy();
        }

        // record completed stats
        long threadUsageNanos = taskHandle.getThreadUsageNanos();
        int priorityLevel = calculatePriorityLevel(threadUsageNanos);
        completedTasksPerLevel.incrementAndGet(priorityLevel);

        // replace blocked splits that were terminated
        addNewEntrants();
    }

    public List<ListenableFuture<?>> enqueueSplits(TaskHandle taskHandle, boolean intermediate, List<? extends SplitRunner> taskSplits)
    {
        List<PrioritizedSplitRunner> splitsToDestroy = new ArrayList<>();
        List<ListenableFuture<?>> finishedFutures = new ArrayList<>(taskSplits.size());
        synchronized (this) {
            for (SplitRunner taskSplit : taskSplits) {
                PrioritizedSplitRunner prioritizedSplitRunner = new PrioritizedSplitRunner(
                        taskHandle,
                        taskSplit,
                        ticker,
                        globalCpuTimeMicros,
                        globalScheduledTimeMicros,
                        blockedQuantaWallTime,
                        unblockedQuantaWallTime);

                if (taskHandle.isDestroyed()) {
                    // If the handle is destroyed, we destroy the task splits to complete the future
                    splitsToDestroy.add(prioritizedSplitRunner);
                }
                else if (intermediate) {
                    // Note: we do not record queued time for intermediate splits
                    startIntermediateSplit(prioritizedSplitRunner);
                    // add the runner to the handle so it can be destroyed if the task is canceled
                    taskHandle.recordIntermediateSplit(prioritizedSplitRunner);
                }
                else {
                    // add this to the work queue for the task
                    taskHandle.enqueueSplit(prioritizedSplitRunner);
                    // if task is under the limit for guaranteed splits, start one
                    scheduleTaskIfNecessary(taskHandle);
                    // if globally we have more resources, start more
                    addNewEntrants();
                }

                finishedFutures.add(prioritizedSplitRunner.getFinishedFuture());
            }
        }
        for (PrioritizedSplitRunner split : splitsToDestroy) {
            split.destroy();
        }
        return finishedFutures;
    }

    private void splitFinished(PrioritizedSplitRunner split)
    {
        completedSplitsPerLevel.incrementAndGet(split.getPriorityLevel().get());
        synchronized (this) {
            allSplits.remove(split);

            long wallNanos = System.nanoTime() - split.getCreatedNanos();
            splitWallTime.add(Duration.succinctNanos(wallNanos));

            if (intermediateSplits.remove(split)) {
                intermediateSplitWallTime.add(wallNanos);
                intermediateSplitScheduledTime.add(split.getScheduledNanos());
                intermediateSplitWaitTime.add(split.getWaitNanos());
            }
            else {
                leafSplitWallTime.add(wallNanos);
                leafSplitScheduledTime.add(split.getScheduledNanos());
                leafSplitWaitTime.add(split.getWaitNanos());
            }

            TaskHandle taskHandle = split.getTaskHandle();
            taskHandle.splitComplete(split);

            scheduleTaskIfNecessary(taskHandle);

            addNewEntrants();
        }
        // call destroy outside of synchronized block as it is expensive and doesn't need a lock on the task executor
        split.destroy();
    }

    private synchronized void scheduleTaskIfNecessary(TaskHandle taskHandle)
    {
        // if task has less than the minimum guaranteed splits running,
        // immediately schedule a new split for this task.  This assures
        // that a task gets its fair amount of consideration (you have to
        // have splits to be considered for running on a thread).
        if (taskHandle.getRunningLeafSplits() < GUARANTEED_SPLITS_PER_TASK) {
            PrioritizedSplitRunner split = taskHandle.pollNextSplit();
            if (split != null) {
                startSplit(split);
                splitQueuedTime.add(Duration.nanosSince(split.getCreatedNanos()));
            }
        }
    }

    private synchronized void addNewEntrants()
    {
        // Ignore intermediate splits when checking minimumNumberOfDrivers.
        // Otherwise with (for example) minimumNumberOfDrivers = 100, 200 intermediate splits
        // and 100 leaf splits, depending on order of appearing splits, number of
        // simultaneously running splits may vary. If leaf splits start first, there will
        // be 300 running splits. If intermediate splits start first, there will be only
        // 200 running splits.
        int running = allSplits.size() - intermediateSplits.size();
        for (int i = 0; i < minimumNumberOfDrivers - running; i++) {
            PrioritizedSplitRunner split = pollNextSplitWorker();
            if (split == null) {
                break;
            }

            splitQueuedTime.add(Duration.nanosSince(split.getCreatedNanos()));
            startSplit(split);
        }
    }

    private synchronized void startIntermediateSplit(PrioritizedSplitRunner split)
    {
        startSplit(split);
        intermediateSplits.add(split);
    }

    private synchronized void startSplit(PrioritizedSplitRunner split)
    {
        allSplits.add(split);
        waitingSplits.put(split);
    }

    private synchronized PrioritizedSplitRunner pollNextSplitWorker()
    {
        // todo find a better algorithm for this
        // find the first task that produces a split, then move that task to the
        // end of the task list, so we get round robin
        for (Iterator<TaskHandle> iterator = tasks.iterator(); iterator.hasNext(); ) {
            TaskHandle task = iterator.next();
            PrioritizedSplitRunner split = task.pollNextSplit();
            if (split != null) {
                // move task to end of list
                iterator.remove();

                // CAUTION: we are modifying the list in the loop which would normally
                // cause a ConcurrentModificationException but we exit immediately
                tasks.add(task);
                return split;
            }
        }
        return null;
    }

    private void monitorActiveSplits()
    {
        for (RunningSplitInfo splitInfo : runningSplitInfos) {
            Duration duration = Duration.succinctNanos(ticker.read() - splitInfo.getStartTime());
            if (duration.compareTo(LONG_SPLIT_WARNING_THRESHOLD) < 0) {
                return;
            }
            if (splitInfo.isPrinted()) {
                continue;
            }
            splitInfo.setPrinted();

            String currentMaxActiveSplit = splitInfo.getThreadId();
            Exception exception = new Exception("Long running split");
            exception.setStackTrace(splitInfo.getThread().getStackTrace());
            log.warn(exception, "Split thread %s has been running longer than %s", currentMaxActiveSplit, duration);
        }
    }

    private class TaskRunner
            implements Runnable
    {
        private final long runnerId = NEXT_RUNNER_ID.getAndIncrement();

        @Override
        public void run()
        {
            try (SetThreadName runnerName = new SetThreadName("SplitRunner-%s", runnerId)) {
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    // select next worker
                    final PrioritizedSplitRunner split;
                    try {
                        split = waitingSplits.take();
                        if (split.updatePriorityLevel()) {
                            // priority level changed, return split to queue for re-prioritization
                            waitingSplits.put(split);
                            continue;
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    selectedLevelCounters[split.getPriorityLevel().get()].update(1);
                    String threadId = split.getTaskHandle().getTaskId() + "-" + split.getSplitId();
                    try (SetThreadName splitName = new SetThreadName(threadId)) {
                        RunningSplitInfo splitInfo = new RunningSplitInfo(ticker.read(), threadId, Thread.currentThread());
                        runningSplitInfos.add(splitInfo);
                        runningSplits.add(split);

                        ListenableFuture<?> blocked;
                        try {
                            blocked = split.process();
                        }
                        finally {
                            runningSplitInfos.remove(splitInfo);
                            runningSplits.remove(split);
                        }

                        if (split.isFinished()) {
                            log.debug("%s is finished", split.getInfo());
                            splitFinished(split);
                        }
                        else {
                            if (blocked.isDone()) {
                                waitingSplits.put(split);
                            }
                            else {
                                blockedSplits.put(split, blocked);
                                blocked.addListener(() -> {
                                    blockedSplits.remove(split);
                                    split.updatePriorityLevel();
                                    split.setReady();
                                    waitingSplits.put(split);
                                }, executor);
                            }
                        }
                    }
                    catch (Throwable t) {
                        // ignore random errors due to driver thread interruption
                        if (!split.isDestroyed()) {
                            if (t instanceof PrestoException) {
                                PrestoException e = (PrestoException) t;
                                log.error("Error processing %s: %s: %s", split.getInfo(), e.getErrorCode().getName(), e.getMessage());
                            }
                            else {
                                log.error(t, "Error processing %s", split.getInfo());
                            }
                        }
                        splitFinished(split);
                    }
                }
            }
            finally {
                // unless we have been closed, we need to replace this thread
                if (!closed) {
                    addRunnerThread();
                }
            }
        }
    }

    //
    // STATS
    //

    @Managed
    public synchronized int getTasks()
    {
        return tasks.size();
    }

    @Managed
    public int getRunnerThreads()
    {
        return runnerThreads;
    }

    @Managed
    public int getMinimumNumberOfDrivers()
    {
        return minimumNumberOfDrivers;
    }

    @Managed
    public synchronized int getTotalSplits()
    {
        return allSplits.size();
    }

    @Managed
    public synchronized int getIntermediateSplits()
    {
        return intermediateSplits.size();
    }

    @Managed
    public int getWaitingSplits()
    {
        return waitingSplits.size();
    }

    @Managed
    public int getRunningSplits()
    {
        return runningSplits.size();
    }

    @Managed
    public int getBlockedSplits()
    {
        return blockedSplits.size();
    }

    @Managed
    public long getCompletedTasksLevel0()
    {
        return completedTasksPerLevel.get(0);
    }

    @Managed
    public long getCompletedTasksLevel1()
    {
        return completedTasksPerLevel.get(1);
    }

    @Managed
    public long getCompletedTasksLevel2()
    {
        return completedTasksPerLevel.get(2);
    }

    @Managed
    public long getCompletedTasksLevel3()
    {
        return completedTasksPerLevel.get(3);
    }

    @Managed
    public long getCompletedTasksLevel4()
    {
        return completedTasksPerLevel.get(4);
    }

    @Managed
    public long getCompletedSplitsLevel0()
    {
        return completedSplitsPerLevel.get(0);
    }

    @Managed
    public long getCompletedSplitsLevel1()
    {
        return completedSplitsPerLevel.get(1);
    }

    @Managed
    public long getCompletedSplitsLevel2()
    {
        return completedSplitsPerLevel.get(2);
    }

    @Managed
    public long getCompletedSplitsLevel3()
    {
        return completedSplitsPerLevel.get(3);
    }

    @Managed
    public long getCompletedSplitsLevel4()
    {
        return completedSplitsPerLevel.get(4);
    }

    @Managed
    public long getRunningTasksLevel0()
    {
        return calculateRunningTasksForLevel(0);
    }

    @Managed
    public long getRunningTasksLevel1()
    {
        return calculateRunningTasksForLevel(1);
    }

    @Managed
    public long getRunningTasksLevel2()
    {
        return calculateRunningTasksForLevel(2);
    }

    @Managed
    public long getRunningTasksLevel3()
    {
        return calculateRunningTasksForLevel(3);
    }

    @Managed
    public long getRunningTasksLevel4()
    {
        return calculateRunningTasksForLevel(4);
    }

    @Managed
    public CounterStat getSelectedCountLevel0()
    {
        return selectedLevelCounters[0];
    }

    @Managed
    public CounterStat getSelectedCountLevel1()
    {
        return selectedLevelCounters[1];
    }

    @Managed
    public CounterStat getSelectedCountLevel2()
    {
        return selectedLevelCounters[2];
    }

    @Managed
    public CounterStat getSelectedCountLevel3()
    {
        return selectedLevelCounters[3];
    }

    @Managed
    public CounterStat getSelectedCountLevel4()
    {
        return selectedLevelCounters[4];
    }

    @Managed
    @Nested
    public TimeStat getSplitQueuedTime()
    {
        return splitQueuedTime;
    }

    @Managed
    @Nested
    public TimeStat getSplitWallTime()
    {
        return splitWallTime;
    }

    @Managed
    @Nested
    public TimeStat getBlockedQuantaWallTime()
    {
        return blockedQuantaWallTime;
    }

    @Managed
    @Nested
    public TimeStat getUnblockedQuantaWallTime()
    {
        return unblockedQuantaWallTime;
    }

    @Managed
    @Nested
    public TimeDistribution getLeafSplitScheduledTime()
    {
        return leafSplitScheduledTime;
    }

    @Managed
    @Nested
    public TimeDistribution getIntermediateSplitScheduledTime()
    {
        return intermediateSplitScheduledTime;
    }

    @Managed
    @Nested
    public TimeDistribution getLeafSplitWallTime()
    {
        return leafSplitWallTime;
    }

    @Managed
    @Nested
    public TimeDistribution getIntermediateSplitWallTime()
    {
        return intermediateSplitWallTime;
    }

    @Managed
    @Nested
    public CounterStat getGlobalScheduledTimeMicros()
    {
        return globalScheduledTimeMicros;
    }

    @Managed
    @Nested
    public CounterStat getGlobalCpuTimeMicros()
    {
        return globalCpuTimeMicros;
    }

    private synchronized int calculateRunningTasksForLevel(int level)
    {
        int count = 0;
        for (TaskHandle task : tasks) {
            if (calculatePriorityLevel(task.getThreadUsageNanos()) == level) {
                count++;
            }
        }
        return count;
    }

    @Managed
    public long getMaxActiveSplitTime()
    {
        Iterator<RunningSplitInfo> iterator = runningSplitInfos.iterator();
        if (iterator.hasNext()) {
            return NANOSECONDS.toMillis(ticker.read() - iterator.next().getStartTime());
        }
        return 0;
    }

    private static class RunningSplitInfo
            implements Comparable<RunningSplitInfo>
    {
        private final long startTime;
        private final String threadId;
        private final Thread thread;
        private boolean printed;

        public RunningSplitInfo(long startTime, String threadId, Thread thread)
        {
            this.startTime = startTime;
            this.threadId = threadId;
            this.thread = thread;
            this.printed = false;
        }

        public long getStartTime()
        {
            return startTime;
        }

        public String getThreadId()
        {
            return threadId;
        }

        public Thread getThread()
        {
            return thread;
        }

        public boolean isPrinted()
        {
            return printed;
        }

        public void setPrinted()
        {
            printed = true;
        }

        @Override
        public int compareTo(RunningSplitInfo o)
        {
            return ComparisonChain.start()
                    .compare(startTime, o.getStartTime())
                    .compare(threadId, o.getThreadId())
                    .result();
        }
    }

    @Managed(description = "Task processor executor")
    @Nested
    public ThreadPoolExecutorMBean getProcessorExecutor()
    {
        return executorMBean;
    }
}
package com.droidagentkit.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class JobState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED }

data class ManagedJobSpec(
    val id: String,
    val operation: AuthorizedOperation,
    val command: CommandSpec,
    val timeoutSeconds: Long,
    val cleanup: () -> Unit = {},
)

data class JobSnapshot(
    val id: String,
    val state: JobState,
    val artifact: ArtifactRef? = null,
    val redactedTail: String = "",
    val warnings: List<String> = emptyList(),
)

interface ManagedJobRunner {
    fun start(spec: ManagedJobSpec): JobSnapshot

    fun status(id: String): JobSnapshot

    fun cancel(id: String): JobSnapshot
}

class InProcessManagedJobRunner(
    private val runner: ProcessRunner,
    private val maxReadOnlyConcurrency: Int = 4,
    private val ttlSeconds: Long = 3600L,
) : ManagedJobRunner {
    private val jobs = ConcurrentHashMap<String, LiveJob>()
    private val deviceLocks = ConcurrentHashMap<String, Semaphore>()
    private val readOnlySlots = Semaphore(maxReadOnlyConcurrency)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "droidagentkit-jobs").apply { isDaemon = true } }

    init {
        scheduler.scheduleAtFixedRate({ expireStale() }, ttlSeconds, ttlSeconds, TimeUnit.SECONDS)
    }

    override fun start(spec: ManagedJobSpec): JobSnapshot {
        val lock = deviceLock(spec.operation.request)
        if (spec.operation.request.mutating) {
            if (!lock.tryAcquire()) {
                return JobSnapshot(spec.id, JobState.PENDING, warnings = listOf("device-busy"))
            }
        } else {
            if (!readOnlySlots.tryAcquire()) {
                return JobSnapshot(spec.id, JobState.PENDING, warnings = listOf("read-concurrency-full"))
            }
        }
        val live = LiveJob(spec, JobState.RUNNING)
        jobs[spec.id] = live
        val executor = Executors.newSingleThreadExecutor()
        live.executor = executor
        live.future =
            executor.submit<JobSnapshot> {
                val result = runner.run(spec.command) { proc -> live.process = proc }
                val state = if (result.status == ResultStatus.SUCCESS) JobState.SUCCEEDED else JobState.FAILED
                val snapshot = JobSnapshot(spec.id, state, result.artifacts.firstOrNull(), warnings = result.warnings)
                releaseLock(spec.operation.request, lock)
                runCleanup(live)
                live.complete(state, snapshot)
                snapshot
            }
        scheduler.schedule({ timeoutIfRunning(spec.id) }, spec.timeoutSeconds, TimeUnit.SECONDS)
        return JobSnapshot(spec.id, JobState.RUNNING)
    }

    override fun status(id: String): JobSnapshot {
        val live = jobs[id] ?: return JobSnapshot(id, JobState.EXPIRED, warnings = listOf("unknown-job"))
        val snapshot = live.snapshot
        return snapshot ?: JobSnapshot(id, live.state)
    }

    override fun cancel(id: String): JobSnapshot {
        val live = jobs[id] ?: return JobSnapshot(id, JobState.EXPIRED, warnings = listOf("unknown-job"))
        live.process
            ?.toHandle()
            ?.descendants()
            ?.forEach(ProcessHandle::destroy)
        live.process?.destroy()
        live.executor?.shutdownNow()
        runCleanup(live)
        val snapshot = JobSnapshot(id, JobState.CANCELLED, warnings = listOf("cancelled"))
        live.complete(JobState.CANCELLED, snapshot)
        releaseLock(spec(live).operation.request, deviceLock(spec(live).operation.request))
        return snapshot
    }

    private fun timeoutIfRunning(id: String) {
        val live = jobs[id] ?: return
        if (live.state == JobState.RUNNING) {
            live.process
                ?.toHandle()
                ?.descendants()
                ?.forEach(ProcessHandle::destroy)
            live.process?.destroy()
            runCleanup(live)
            val snapshot = JobSnapshot(id, JobState.FAILED, warnings = listOf("command-timeout"))
            live.complete(JobState.FAILED, snapshot)
            releaseLock(spec(live).operation.request, deviceLock(spec(live).operation.request))
        }
    }

    private fun expireStale() {
        val now = Instant.now()
        jobs.forEach { (id, live) ->
            if (live.state == JobState.RUNNING && Duration.between(live.startedAt, now).seconds > ttlSeconds) {
                runCleanup(live)
                val snapshot = JobSnapshot(id, JobState.EXPIRED, warnings = listOf("job-expired"))
                live.complete(JobState.EXPIRED, snapshot)
                releaseLock(spec(live).operation.request, deviceLock(spec(live).operation.request))
            }
        }
    }

    private fun runCleanup(live: LiveJob) {
        if (live.cleanupRan.compareAndSet(false, true)) {
            runCatching { spec(live).cleanup() }
        }
    }

    private fun deviceLock(request: OperationRequest): Semaphore =
        request.deviceSerial?.let { deviceLocks.computeIfAbsent(it) { Semaphore(1) } } ?: readOnlySlots

    private fun releaseLock(
        request: OperationRequest,
        lock: Semaphore,
    ) {
        if (request.deviceSerial != null) {
            lock.release()
        } else {
            readOnlySlots.release()
        }
    }

    private fun spec(live: LiveJob): ManagedJobSpec = live.spec

    private class LiveJob(
        val spec: ManagedJobSpec,
        @Volatile var state: JobState,
    ) {
        val startedAt: Instant = Instant.now()

        @Volatile var process: Process? = null

        @Volatile var executor: java.util.concurrent.ExecutorService? = null

        @Volatile var future: java.util.concurrent.Future<JobSnapshot>? = null

        @Volatile var snapshot: JobSnapshot? = null

        val cleanupRan = AtomicBoolean(false)

        fun complete(
            newState: JobState,
            result: JobSnapshot,
        ) {
            state = newState
            snapshot = result
        }
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }
}

package cn.suhoan.anaxa.benchmark;

import cn.suhoan.anaxa.common.json.JsonSupport;
import cn.suhoan.anaxa.common.model.CollectionStats;
import cn.suhoan.anaxa.common.model.CreateCollectionRequest;
import cn.suhoan.anaxa.common.model.SearchRequest;
import cn.suhoan.anaxa.common.model.SearchResponse;
import cn.suhoan.anaxa.common.model.UpsertVector;
import cn.suhoan.anaxa.common.model.UpsertVectorsRequest;
import cn.suhoan.anaxa.server.AnaxaHttpServer;
import cn.suhoan.anaxa.server.ServerConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EnvironmentBenchmarkRunner {
    private static final Duration QUIESCENT_TIMEOUT = Duration.ofMinutes(5L);
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.systemDefault());

    private final BenchmarkConfig config;
    private final List<BenchmarkScenario> scenarios;

    public EnvironmentBenchmarkRunner(BenchmarkConfig config) {
        this(config, config.preset().scenarios(config.defaultFlushThresholdBytes()));
    }

    public EnvironmentBenchmarkRunner(BenchmarkConfig config, List<BenchmarkScenario> scenarios) {
        this.config = Objects.requireNonNull(config, "config");
        this.scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        if (this.scenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty");
        }
    }

    public BenchmarkReport run() throws Exception {
        String runId = RUN_ID_FORMAT.format(Instant.now()) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path runDirectory = config.dataDirectory().resolve(runId);
        AnaxaHttpServer embeddedServer = null;
        boolean deleteRunDirectory = config.embeddedServer() && !config.keepData();

        try {
            String baseUrl = config.baseUrl();
            if (config.embeddedServer()) {
                Files.createDirectories(runDirectory);
                embeddedServer = new AnaxaHttpServer(new ServerConfig(
                        config.host(),
                        config.port(),
                        runDirectory,
                        config.defaultFlushThresholdBytes(),
                        java.util.Set.of(),
                        null,
                        config.rateLimitPerMinute(),
                        config.rateLimitBurst(),
                        config.slowQueryThresholdMillis(),
                        runDirectory.resolve("audit").resolve("audit.log"),
                        runDirectory.resolve("backups"),
                        0L,
                        7,
                        ServerConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                        ServerConfig.DEFAULT_MAX_CONCURRENT_REQUESTS,
                        true
                ));
                embeddedServer.start();
                baseUrl = "http://%s:%d".formatted(config.host(), embeddedServer.port());
            }

            BenchmarkHttpClient client = new BenchmarkHttpClient(baseUrl, config.apiKey(), config.tenantId(), config.requestTimeout());
            EnvironmentSnapshot environment = captureEnvironment(baseUrl, runDirectory, runId);
            ArrayList<ScenarioResult> results = new ArrayList<>(scenarios.size());
            for (BenchmarkScenario scenario : scenarios) {
                results.add(runScenario(client, scenario, runId));
            }
            return new BenchmarkReport(config, environment, List.copyOf(results));
        } finally {
            if (embeddedServer != null) {
                embeddedServer.close();
            }
            if (deleteRunDirectory) {
                deleteRecursively(runDirectory);
            }
        }
    }

    private EnvironmentSnapshot captureEnvironment(String baseUrl, Path runDirectory, String runId) {
        Runtime runtime = Runtime.getRuntime();
        return new EnvironmentSnapshot(
                baseUrl,
                config.embeddedServer(),
                runDirectory,
                runId,
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                runtime.availableProcessors(),
                runtime.maxMemory(),
                runtime.totalMemory(),
                runtime.freeMemory()
        );
    }

    private ScenarioResult runScenario(BenchmarkHttpClient client, BenchmarkScenario scenario, String runId) throws Exception {
        String collectionName = collectionNameFor(runId, scenario);
        CollectionStats created = client.createCollection(new CreateCollectionRequest(
                collectionName,
                scenario.dimension(),
                scenario.metric(),
                scenario.flushThresholdBytes()
        ));

        double ingestStarted = secondsNow();
        CollectionStats latestStats = created;
        for (int start = 0; start < scenario.vectorCount(); start += scenario.ingestBatchSize()) {
            int end = Math.min(scenario.vectorCount(), start + scenario.ingestBatchSize());
            ArrayList<UpsertVector> vectors = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                vectors.add(new UpsertVector(
                        "doc-%d".formatted(index),
                        vectorFor(index, scenario.dimension()),
                        payloadFor(index, scenario.groupCount())
                ));
            }
            latestStats = client.upsert(collectionName, new UpsertVectorsRequest(vectors));
        }
        double ingestDurationSeconds = secondsNow() - ingestStarted;
        double ingestThroughputVps = scenario.vectorCount() / Math.max(ingestDurationSeconds, 1.0e-9d);

        double prepareDurationSeconds = 0.0d;
        CollectionStats preparedStats = latestStats;
        if (scenario.prepareMode() != PrepareMode.NONE) {
            double prepareStarted = secondsNow();
            preparedStats = client.flush(collectionName);
            preparedStats = waitForQuiescent(client, collectionName);
            if (scenario.prepareMode() == PrepareMode.FLUSH_AND_COMPACT) {
                preparedStats = client.compact(collectionName);
                preparedStats = waitForQuiescent(client, collectionName);
            }
            prepareDurationSeconds = secondsNow() - prepareStarted;
        }

        SearchPhaseResult warmup = runSearchPhase(client, collectionName, scenario, scenario.warmupRequests(), "warmup");
        SearchPhaseResult measured = runSearchPhase(client, collectionName, scenario, scenario.searchRequests(), "measured-search");
        CollectionStats finalStats = client.stats(collectionName);
        MetricsSnapshot metricsSnapshot = metricsSnapshot(client, collectionName);

        return new ScenarioResult(
                scenario,
                collectionName,
                ingestDurationSeconds,
                ingestThroughputVps,
                prepareDurationSeconds,
                preparedStats,
                warmup,
                measured,
                finalStats,
                metricsSnapshot
        );
    }

    private MetricsSnapshot metricsSnapshot(BenchmarkHttpClient client, String collectionName) {
        try {
            HttpResponsePayload response = client.metrics();
            if (response.statusCode() != 200) {
                return new MetricsSnapshot(false, List.of("metrics_unavailable_status=" + response.statusCode()));
            }
            List<String> lines = response.body()
                    .lines()
                    .filter(line -> line.contains("collection=\"" + collectionName + "\""))
                    .filter(EnvironmentBenchmarkRunner::isBaselineMetric)
                    .sorted()
                    .toList();
            return new MetricsSnapshot(true, lines);
        } catch (Exception exception) {
            return new MetricsSnapshot(false, List.of("metrics_unavailable_exception=" + exception.getClass().getSimpleName()));
        }
    }

    private static boolean isBaselineMetric(String line) {
        return line.startsWith("anaxa_search_")
                || line.startsWith("anaxa_engine_active_searches")
                || line.startsWith("anaxa_engine_pending_flush_memtables")
                || line.startsWith("anaxa_engine_queued_warm_tasks")
                || line.startsWith("anaxa_engine_resident_")
                || line.startsWith("anaxa_engine_flush_in_progress")
                || line.startsWith("anaxa_engine_compaction_in_progress")
                || line.startsWith("anaxa_background_");
    }

    private SearchPhaseResult runSearchPhase(
            BenchmarkHttpClient client,
            String collectionName,
            BenchmarkScenario scenario,
            int requestCount,
            String phaseName
    ) throws Exception {
        if (requestCount <= 0) {
            return new SearchPhaseResult(
                    phaseName,
                    0.0d,
                    0,
                    0,
                    0,
                    0.0d,
                    0.0d,
                    LatencySummary.empty(),
                    Map.of()
            );
        }

        int[] requestsPerWorker = new int[scenario.searchWorkers()];
        for (int index = 0; index < requestCount; index++) {
            requestsPerWorker[index % requestsPerWorker.length]++;
        }

        double started = secondsNow();
        ArrayList<Double> latencies = new ArrayList<>(requestCount);
        TreeMap<String, Integer> statusCounts = new TreeMap<>();
        int errors = 0;

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("anaxa-bench-search-", 0L).factory())) {
            ArrayList<Callable<WorkerPhaseResult>> tasks = new ArrayList<>(requestsPerWorker.length);
            for (int workerId = 0; workerId < requestsPerWorker.length; workerId++) {
                int workerRequests = requestsPerWorker[workerId];
                if (workerRequests == 0) {
                    continue;
                }
                final int currentWorkerId = workerId;
                tasks.add(() -> runWorker(client, collectionName, scenario, currentWorkerId, workerRequests));
            }

            for (var future : executor.invokeAll(tasks)) {
                WorkerPhaseResult worker = future.get();
                latencies.addAll(worker.latenciesMillis());
                errors += worker.errors();
                worker.statusCounts().forEach((status, count) -> statusCounts.merge(status, count, Integer::sum));
            }
        }

        double durationSeconds = secondsNow() - started;
        int successful = latencies.size();
        return new SearchPhaseResult(
                phaseName,
                durationSeconds,
                requestCount,
                successful,
                errors,
                requestCount / Math.max(durationSeconds, 1.0e-9d),
                successful / Math.max(durationSeconds, 1.0e-9d),
                LatencySummary.from(latencies),
                Map.copyOf(statusCounts)
        );
    }

    private WorkerPhaseResult runWorker(
            BenchmarkHttpClient client,
            String collectionName,
            BenchmarkScenario scenario,
            int workerId,
            int requestCount
    ) {
        ArrayList<Double> latencies = new ArrayList<>(requestCount);
        TreeMap<String, Integer> statusCounts = new TreeMap<>();
        int errors = 0;

        for (int iteration = 0; iteration < requestCount; iteration++) {
            int vectorIndex = (workerId + iteration * scenario.searchWorkers()) % scenario.vectorCount();
            SearchRequest request = new SearchRequest(
                    vectorFor(vectorIndex, scenario.dimension()),
                    scenario.topK(),
                    filterFor(vectorIndex, scenario)
            );
            double started = secondsNow();
            try {
                HttpResponsePayload response = client.request("POST", "/collections/" + collectionName + "/search", request);
                double elapsedMillis = (secondsNow() - started) * 1_000.0d;
                statusCounts.merge(Integer.toString(response.statusCode()), 1, Integer::sum);
                if (response.statusCode() != 200) {
                    errors++;
                    continue;
                }
                SearchResponse body = JsonSupport.mapper().readValue(response.body(), SearchResponse.class);
                if (body.hits() == null) {
                    errors++;
                    statusCounts.merge("invalid-body", 1, Integer::sum);
                    continue;
                }
                latencies.add(elapsedMillis);
            } catch (Exception exception) {
                errors++;
                statusCounts.merge("exception", 1, Integer::sum);
            }
        }

        return new WorkerPhaseResult(List.copyOf(latencies), errors, Map.copyOf(statusCounts));
    }

    private CollectionStats waitForQuiescent(BenchmarkHttpClient client, String collectionName) throws Exception {
        long deadlineNanos = System.nanoTime() + QUIESCENT_TIMEOUT.toNanos();
        CollectionStats latest = client.stats(collectionName);
        while (latest.flushInProgress() || latest.compactionInProgress()) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("Collection %s did not become quiescent within %s".formatted(collectionName, QUIESCENT_TIMEOUT));
            }
            Thread.sleep(50L);
            latest = client.stats(collectionName);
        }
        return latest;
    }

    private String collectionNameFor(String runId, BenchmarkScenario scenario) {
        String raw = config.collectionPrefix() + "-" + runId + "-" + scenario.name();
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-");
    }

    private static float[] vectorFor(int index, int dimension) {
        float[] vector = new float[dimension];
        vector[index % dimension] = 1.0F;
        vector[(index * 7 + 3) % dimension] += 0.35F;
        vector[(index * 11 + 5) % dimension] += 0.2F;
        vector[(index * 13 + 1) % dimension] += ((index % 5) + 1) / 20.0F;
        return vector;
    }

    private static Map<String, Object> payloadFor(int index, int groupCount) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("bucket", index % 1_024);
        payload.put("source", "repo-" + (index % Math.max(1, groupCount == 0 ? 1 : groupCount)));
        payload.put("path", "/docs/" + (index % 97) + "/note-" + index + ".md");
        payload.put("section", index % 12);
        if (groupCount > 0) {
            payload.put("group", "g-" + (index % groupCount));
        }
        return payload;
    }

    private static Map<String, Object> filterFor(int vectorIndex, BenchmarkScenario scenario) {
        if (!scenario.useFilter() || scenario.groupCount() <= 0) {
            return Map.of();
        }
        return Map.of("group", "g-" + (vectorIndex % scenario.groupCount()));
    }

    private static double secondsNow() {
        return System.nanoTime() / 1_000_000_000.0d;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to delete benchmark directory " + root, exception);
        }
    }

    private static double percentile(List<Double> values, double ratio) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        ArrayList<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(sorted.size() * ratio) - 1));
        return sorted.get(index);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public record BenchmarkReport(
            BenchmarkConfig config,
            EnvironmentSnapshot environment,
            List<ScenarioResult> results
    ) {
        public String render() {
            StringBuilder builder = new StringBuilder();
            builder.append("[environment]\n");
            builder.append("  profile:            ").append(config.preset().cliValue()).append('\n');
            builder.append("  server_mode:        ").append(environment.embeddedServer() ? "embedded" : "remote").append('\n');
            builder.append("  base_url:           ").append(environment.baseUrl()).append('\n');
            builder.append("  run_id:             ").append(environment.runId()).append('\n');
            builder.append("  data_dir:           ").append(environment.dataDirectory().toAbsolutePath()).append('\n');
            builder.append("  java_runtime:       ").append(environment.javaRuntimeVersion()).append('\n');
            builder.append("  vm_name:            ").append(environment.vmName()).append('\n');
            builder.append("  os:                 ").append(environment.osName()).append(' ').append(environment.osVersion()).append('\n');
            builder.append("  available_cpu:      ").append(environment.availableProcessors()).append('\n');
            builder.append("  max_heap_bytes:     ").append(environment.maxHeapBytes()).append('\n');
            builder.append("  total_heap_bytes:   ").append(environment.totalHeapBytes()).append('\n');
            builder.append("  free_heap_bytes:    ").append(environment.freeHeapBytes()).append('\n');

            for (ScenarioResult result : results) {
                builder.append('\n')
                        .append("[scenario:")
                        .append(result.scenario().name())
                        .append("]\n");
                builder.append("  collection:         ").append(result.collectionName()).append('\n');
                builder.append("  family:             ").append(result.scenario().family()).append('\n');
                builder.append("  prepare_mode:       ").append(result.scenario().prepareMode().label()).append('\n');
                builder.append("  dimension:          ").append(result.scenario().dimension()).append('\n');
                builder.append("  vectors:            ").append(result.scenario().vectorCount()).append('\n');
                builder.append("  search_workers:     ").append(result.scenario().searchWorkers()).append('\n');
                builder.append("  use_filter:         ").append(result.scenario().useFilter()).append('\n');
                builder.append("  ingest_seconds:     ").append(formatDouble(result.ingestDurationSeconds())).append('\n');
                builder.append("  ingest_vps:         ").append(formatDouble(result.ingestThroughputVps())).append('\n');
                if (result.scenario().prepareMode() != PrepareMode.NONE) {
                    builder.append("  prepare_seconds:    ").append(formatDouble(result.prepareDurationSeconds())).append('\n');
                }
                appendPhase(builder, result.warmup());
                appendPhase(builder, result.measured());
                builder.append("  segments:           ").append(result.finalStats().segmentCount()).append('\n');
                builder.append("  storage_bytes:      ").append(result.finalStats().storageBytes()).append('\n');
                appendMetricsSnapshot(builder, result.metricsSnapshot());
            }

            builder.append('\n').append("[summary]\n");
            builder.append(String.format(
                    Locale.ROOT,
                    "%-28s %-14s %6s %8s %8s %7s %13s %14s %14s %14s%n",
                    "scenario",
                    "prepare",
                    "dim",
                    "vectors",
                    "workers",
                    "filter",
                    "ingest_vps",
                    "warmup_p99",
                    "measured_qps",
                    "measured_p99"
            ));
            for (ScenarioResult result : results) {
                builder.append(String.format(
                        Locale.ROOT,
                        "%-28s %-14s %6d %8d %8d %7s %13.2f %14.3f %14.2f %14.3f%n",
                        result.scenario().name(),
                        result.scenario().prepareMode().label(),
                        result.scenario().dimension(),
                        result.scenario().vectorCount(),
                        result.scenario().searchWorkers(),
                        Boolean.toString(result.scenario().useFilter()),
                        result.ingestThroughputVps(),
                        result.warmup().latency().p99Millis(),
                        result.measured().successfulQps(),
                        result.measured().latency().p99Millis()
                ));
            }

            List<String> observations = observations(results);
            if (!observations.isEmpty()) {
                builder.append('\n').append("[observations]\n");
                for (String observation : observations) {
                    builder.append("- ").append(observation).append('\n');
                }
            }
            return builder.toString();
        }

        private static void appendMetricsSnapshot(StringBuilder builder, MetricsSnapshot metricsSnapshot) {
            builder.append("  metrics_available:  ").append(metricsSnapshot.available()).append('\n');
            if (metricsSnapshot.lines().isEmpty()) {
                return;
            }
            builder.append("  metrics_snapshot:\n");
            for (String line : metricsSnapshot.lines()) {
                builder.append("    ").append(line).append('\n');
            }
        }

        private static void appendPhase(StringBuilder builder, SearchPhaseResult phase) {
            builder.append("  ")
                    .append(phase.name())
                    .append("_seconds:    ")
                    .append(formatDouble(phase.durationSeconds()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_success:    ")
                    .append(phase.successful())
                    .append('/')
                    .append(phase.totalRequests())
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_qps:        ")
                    .append(formatDouble(phase.successfulQps()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_p50_ms:     ")
                    .append(formatDouble(phase.latency().p50Millis()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_p95_ms:     ")
                    .append(formatDouble(phase.latency().p95Millis()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_p99_ms:     ")
                    .append(formatDouble(phase.latency().p99Millis()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_max_ms:     ")
                    .append(formatDouble(phase.latency().maxMillis()))
                    .append('\n');
            builder.append("  ")
                    .append(phase.name())
                    .append("_status:     ")
                    .append(JsonSupport.writeString(phase.statusCounts()))
                    .append('\n');
        }

        private static List<String> observations(List<ScenarioResult> results) {
            ArrayList<String> observations = new ArrayList<>();
            results.stream()
                    .max(Comparator.comparingDouble(result -> result.measured().successfulQps()))
                    .ifPresent(best -> observations.add(
                            "Best steady-state throughput was %s at %.2f successful QPS (p99 %.3f ms)."
                                    .formatted(best.scenario().name(), best.measured().successfulQps(), best.measured().latency().p99Millis())
                    ));

            Map<String, List<ScenarioResult>> byFamily = results.stream()
                    .collect(Collectors.groupingBy(result -> result.scenario().family()));
            for (Map.Entry<String, List<ScenarioResult>> entry : byFamily.entrySet()) {
                ScenarioResult none = find(entry.getValue(), PrepareMode.NONE);
                ScenarioResult flush = find(entry.getValue(), PrepareMode.FLUSH);
                ScenarioResult flushCompact = find(entry.getValue(), PrepareMode.FLUSH_AND_COMPACT);

                if (none != null && flush != null) {
                    observations.add(
                            "%s: flush changed warmup p99 from %.3f ms to %.3f ms and measured QPS from %.2f to %.2f."
                                    .formatted(
                                            entry.getKey(),
                                            none.warmup().latency().p99Millis(),
                                            flush.warmup().latency().p99Millis(),
                                            none.measured().successfulQps(),
                                            flush.measured().successfulQps()
                                    )
                    );
                }
                if (flush != null && flushCompact != null) {
                    observations.add(
                            "%s: flush+compact added %.3f s of prepare time compared with flush-only and changed warmup p99 from %.3f ms to %.3f ms."
                                    .formatted(
                                            entry.getKey(),
                                            Math.max(0.0d, flushCompact.prepareDurationSeconds() - flush.prepareDurationSeconds()),
                                            flush.warmup().latency().p99Millis(),
                                            flushCompact.warmup().latency().p99Millis()
                                    )
                    );
                }
            }
            return observations;
        }

        private static ScenarioResult find(List<ScenarioResult> results, PrepareMode mode) {
            return results.stream()
                    .filter(result -> result.scenario().prepareMode() == mode)
                    .findFirst()
                    .orElse(null);
        }
    }

    public record EnvironmentSnapshot(
            String baseUrl,
            boolean embeddedServer,
            Path dataDirectory,
            String runId,
            String javaRuntimeVersion,
            String vmName,
            String osName,
            String osVersion,
            int availableProcessors,
            long maxHeapBytes,
            long totalHeapBytes,
            long freeHeapBytes
    ) {
    }

    public record ScenarioResult(
            BenchmarkScenario scenario,
            String collectionName,
            double ingestDurationSeconds,
            double ingestThroughputVps,
            double prepareDurationSeconds,
            CollectionStats preparedStats,
            SearchPhaseResult warmup,
            SearchPhaseResult measured,
            CollectionStats finalStats,
            MetricsSnapshot metricsSnapshot
    ) {
    }

    public record MetricsSnapshot(
            boolean available,
            List<String> lines
    ) {
    }

    public record SearchPhaseResult(
            String name,
            double durationSeconds,
            int totalRequests,
            int successful,
            int errors,
            double attemptedQps,
            double successfulQps,
            LatencySummary latency,
            Map<String, Integer> statusCounts
    ) {
    }

    public record LatencySummary(
            double averageMillis,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis
    ) {
        static LatencySummary empty() {
            return new LatencySummary(0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        static LatencySummary from(List<Double> latenciesMillis) {
            if (latenciesMillis.isEmpty()) {
                return empty();
            }
            double total = 0.0d;
            double max = 0.0d;
            for (double value : latenciesMillis) {
                total += value;
                max = Math.max(max, value);
            }
            return new LatencySummary(
                    total / latenciesMillis.size(),
                    percentile(latenciesMillis, 0.50d),
                    percentile(latenciesMillis, 0.95d),
                    percentile(latenciesMillis, 0.99d),
                    max
            );
        }
    }

    private record WorkerPhaseResult(
            List<Double> latenciesMillis,
            int errors,
            Map<String, Integer> statusCounts
    ) {
    }

    private record HttpResponsePayload(int statusCode, String body) {
    }

    private static final class BenchmarkHttpClient {
        private final String baseUrl;
        private final String apiKey;
        private final String tenantId;
        private final HttpClient client;
        private final Duration requestTimeout;

        private BenchmarkHttpClient(String baseUrl, String apiKey, String tenantId, Duration requestTimeout) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            this.apiKey = apiKey;
            this.tenantId = tenantId;
            this.requestTimeout = requestTimeout;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(requestTimeout)
                    .build();
        }

        private CollectionStats createCollection(CreateCollectionRequest request) throws Exception {
            HttpResponsePayload response = request("POST", "/collections", request);
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new IllegalStateException("Create collection failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
        }

        private CollectionStats upsert(String collectionName, UpsertVectorsRequest request) throws Exception {
            HttpResponsePayload response = request("POST", "/collections/" + collectionName + "/vectors", request);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Upsert failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
        }

        private CollectionStats flush(String collectionName) throws Exception {
            HttpResponsePayload response = request("POST", "/collections/" + collectionName + "/flush", Map.of());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Flush failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
        }

        private CollectionStats compact(String collectionName) throws Exception {
            HttpResponsePayload response = request("POST", "/collections/" + collectionName + "/compact", Map.of());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Compaction failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
        }

        private CollectionStats stats(String collectionName) throws Exception {
            HttpResponsePayload response = request("GET", "/collections/" + collectionName, null);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Stats failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return JsonSupport.mapper().readValue(response.body(), CollectionStats.class);
        }

        private HttpResponsePayload metrics() throws Exception {
            return request("GET", "/metrics", null);
        }

        private HttpResponsePayload request(String method, String path, Object payload) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("X-API-Key", apiKey);
            }
            if (tenantId != null && !tenantId.isBlank()) {
                request.header("X-Tenant-Id", tenantId);
            }
            if (payload == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(JsonSupport.writeString(payload)));
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResponsePayload(response.statusCode(), response.body());
        }
    }
}

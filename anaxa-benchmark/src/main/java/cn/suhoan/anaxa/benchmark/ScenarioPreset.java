package cn.suhoan.anaxa.benchmark;

import cn.suhoan.anaxa.common.model.MetricType;

import java.util.List;
import java.util.Locale;

public enum ScenarioPreset {
    QUICK("quick") {
        @Override
        public List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes) {
            return List.of(
                    generalScenario("general-small", 128, 8_000, 6, false, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-unprepared", "kb-medium", 384, 12_000, 8, 160, 1_600, 64, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-flush", "kb-medium", 384, 12_000, 8, 160, 1_600, 64, defaultFlushThresholdBytes, PrepareMode.FLUSH)
            );
        }
    },
    STANDARD("standard") {
        @Override
        public List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes) {
            return List.of(
                    generalScenario("general-small", 128, 10_000, 8, false, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-unprepared", "kb-medium", 384, 15_000, 8, 200, 2_000, 64, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-flush", "kb-medium", 384, 15_000, 8, 200, 2_000, 64, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    knowledgeBaseScenario("kb-large-unprepared", "kb-large", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-large-flush", "kb-large", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.FLUSH)
            );
        }
    },
    MARKDOWN_KB("markdown-kb") {
        @Override
        public List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes) {
            return List.of(
                    knowledgeBaseScenario("kb-standard-unprepared", "kb-standard", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-standard-flush", "kb-standard", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    knowledgeBaseScenario("kb-standard-flush-compact", "kb-standard", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.FLUSH_AND_COMPACT)
            );
        }
    },
    SCORER_EVAL("scorer-eval") {
        @Override
        public List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes) {
            return List.of(
                    customScenario("cosine-128-prepared", "cosine-128", 128, MetricType.COSINE, 12_000, 8, 10, 0, false, 200, 2_000, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    customScenario("cosine-384-top50-filter", "topk-filter", 384, MetricType.COSINE, 15_000, 8, 50, 64, true, 200, 2_000, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    customScenario("cosine-384-selective-filter", "selective-filter", 384, MetricType.COSINE, 15_000, 8, 10, 512, true, 200, 2_000, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    customScenario("l2-384-prepared", "l2-384", 384, MetricType.L2, 15_000, 8, 10, 0, false, 200, 2_000, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    customScenario("cosine-1536-highdim", "highdim", 1_536, MetricType.COSINE, 12_000, 12, 10, 128, true, 200, 2_000, defaultFlushThresholdBytes, PrepareMode.FLUSH)
            );
        }
    },
    FULL("full") {
        @Override
        public List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes) {
            return List.of(
                    generalScenario("general-small", 128, 10_000, 8, false, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-unprepared", "kb-medium", 384, 15_000, 8, 200, 2_000, 64, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-medium-flush", "kb-medium", 384, 15_000, 8, 200, 2_000, 64, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    knowledgeBaseScenario("kb-large-unprepared", "kb-large", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-large-flush", "kb-large", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.FLUSH),
                    knowledgeBaseScenario("kb-large-flush-compact", "kb-large", 768, 30_000, 12, 300, 3_000, 128, defaultFlushThresholdBytes, PrepareMode.FLUSH_AND_COMPACT),
                    knowledgeBaseScenario("kb-xlarge-unprepared", "kb-xlarge", 1_536, 20_000, 16, 300, 3_000, 256, defaultFlushThresholdBytes, PrepareMode.NONE),
                    knowledgeBaseScenario("kb-xlarge-flush", "kb-xlarge", 1_536, 20_000, 16, 300, 3_000, 256, defaultFlushThresholdBytes, PrepareMode.FLUSH)
            );
        }
    };

    private final String cliValue;

    ScenarioPreset(String cliValue) {
        this.cliValue = cliValue;
    }

    public abstract List<BenchmarkScenario> scenarios(long defaultFlushThresholdBytes);

    public String cliValue() {
        return cliValue;
    }

    public static ScenarioPreset fromCliValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (ScenarioPreset preset : values()) {
            if (preset.cliValue.equals(normalized)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unknown benchmark profile: " + value);
    }

    private static BenchmarkScenario generalScenario(
            String name,
            int dimension,
            int vectorCount,
            int searchWorkers,
            boolean useFilter,
            long defaultFlushThresholdBytes,
            PrepareMode prepareMode
    ) {
        return new BenchmarkScenario(
                name,
                "general-small",
                dimension,
                MetricType.COSINE,
                Math.min(defaultFlushThresholdBytes, 16L * 1024L * 1024L),
                vectorCount,
                250,
                150,
                1_500,
                searchWorkers,
                10,
                useFilter ? 32 : 0,
                useFilter,
                prepareMode
        );
    }

    private static BenchmarkScenario knowledgeBaseScenario(
            String name,
            String family,
            int dimension,
            int vectorCount,
            int searchWorkers,
            int warmupRequests,
            int searchRequests,
            int groupCount,
            long defaultFlushThresholdBytes,
            PrepareMode prepareMode
    ) {
        return new BenchmarkScenario(
                name,
                family,
                dimension,
                MetricType.COSINE,
                Math.min(defaultFlushThresholdBytes, 16L * 1024L * 1024L),
                vectorCount,
                100,
                warmupRequests,
                searchRequests,
                searchWorkers,
                10,
                groupCount,
                true,
                prepareMode
        );
    }

    private static BenchmarkScenario customScenario(
            String name,
            String family,
            int dimension,
            MetricType metric,
            int vectorCount,
            int searchWorkers,
            int topK,
            int groupCount,
            boolean useFilter,
            int warmupRequests,
            int searchRequests,
            long defaultFlushThresholdBytes,
            PrepareMode prepareMode
    ) {
        return new BenchmarkScenario(
                name,
                family,
                dimension,
                metric,
                Math.min(defaultFlushThresholdBytes, 16L * 1024L * 1024L),
                vectorCount,
                100,
                warmupRequests,
                searchRequests,
                searchWorkers,
                topK,
                groupCount,
                useFilter,
                prepareMode
        );
    }
}

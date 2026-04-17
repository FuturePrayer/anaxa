package cn.suhoan.anaxa.benchmark;

public final class AnaxaBenchmarkMain {
    private AnaxaBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.print(consoleText(BenchmarkConfig.usage()));
            return;
        }

        try {
            BenchmarkConfig config = BenchmarkConfig.fromArgs(args);
            EnvironmentBenchmarkRunner.BenchmarkReport report = new EnvironmentBenchmarkRunner(config).run();
            System.out.print(consoleText(report.render()));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println();
            System.err.print(consoleText(BenchmarkConfig.usage()));
            System.exit(1);
        }
    }

    private static String consoleText(String value) {
        return value.replace("\n", System.lineSeparator());
    }
}

package gr.cytech.sendium.performance;

final class DlrMetricSamples {
    private DlrMetricSamples() { }

    static double parseValue(String line) {
        try {
            return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        } catch (NumberFormatException exception) {
            throw new AssertionError("Invalid DLR metric value in sample: " + line, exception);
        }
    }
}

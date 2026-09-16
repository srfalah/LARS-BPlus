package edu.jouf.larsbplus.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class CsvUtil {
    private CsvUtil() {}

    static BufferedWriter writer(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void row(BufferedWriter out, Object... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.write(',');
            out.write(escape(values[i]));
        }
        out.newLine();
    }

    static String escape(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    static double percentile(List<Long> values, double q) {
        if (values.isEmpty()) return Double.NaN;
        long[] copy = new long[values.size()];
        for (int i = 0; i < values.size(); i++) copy[i] = values.get(i);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.ceil(q * copy.length) - 1;
        idx = Math.max(0, Math.min(copy.length - 1, idx));
        return copy[idx];
    }

    static double mean(List<Long> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (long v : values) sum += v;
        return sum / values.size();
    }
}

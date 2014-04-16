package cz.tul.dic.engine.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class Analyzer1D extends ClusterAnalyzer<Double> {

    private final Map<Integer, Integer> counter = new HashMap<>();

    @Override
    public Double findMajorValue() {
        // find best
        int maxCnt = -1, maxVal = 0, val;
        for (Entry<Integer, Integer> e : counter.entrySet()) {
            val = e.getValue();
            if (val > maxCnt) {
                maxCnt = val;
                maxVal = e.getKey();
            }
        }

        return maxVal * precision;
    }

    @Override
    public void addValue(Double d) {
        final int val = (int) Math.round(d / precision);
        if (counter.containsKey(val)) {
            counter.put(val, counter.get(val) + 1);
        } else {
            counter.put(val, 1);
        }
    }

}
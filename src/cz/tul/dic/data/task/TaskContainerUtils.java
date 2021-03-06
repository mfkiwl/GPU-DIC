/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.data.task;

import cz.tul.dic.data.result.DisplacementResult;
import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.config.Config;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.deformation.DeformationOrder;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.result.Result;
import cz.tul.dic.data.roi.AbstractROI;
import cz.tul.dic.data.roi.RectangleROI;
import cz.tul.dic.data.task.loaders.ConfigLoader;
import cz.tul.dic.engine.KernelPerformanceManager;
import cz.tul.dic.engine.solvers.SolverType;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 *
 * @author Petr Jecmen
 */
public final class TaskContainerUtils {

    private TaskContainerUtils() {
    }

    public static Map<Integer, Integer> getRounds(final TaskContainer tc) {
        final Map<Integer, Integer> result = new TreeMap<>();
        if (tc != null) {
            final Object roundData = tc.getParameter(TaskParameter.ROUND_LIMITS);
            if (roundData == null) {
                for (int r = 0; r < tc.getImages().size() - 1; r++) {
                    result.put(r, r + 1);
                }
            } else {
                final int[] rounds = (int[]) roundData;
                for (int round = rounds[0]; round < rounds[1]; round++) {
                    result.put(round, round + 1);
                }
            }
        }
        return result;
    }

    public static int getFirstRound(final TaskContainer tc) {
        return getRounds(tc).keySet().iterator().next();
    }

    public static int getMaxRoundCount(final TaskContainer tc) {
        return tc.getImages().size() - 1;
    }

    public static double getStretchFactor(final TaskContainer tc, final int endImageIndex) {
        final int startImageIndex = getFirstRound(tc);
        double result = 1.0;
        final Result resultsC = tc.getResult(startImageIndex, endImageIndex);
        final Result dResultsC = tc.getResult(endImageIndex - 1, endImageIndex);
        if (resultsC != null && dResultsC != null) {
            final DisplacementResult resultsDR = resultsC.getDisplacementResult();
            final DisplacementResult dResultsDR = dResultsC.getDisplacementResult();
            if (resultsDR != null && dResultsDR != null) {
                final double[][][] results = resultsDR.getDisplacement();
                final double[][][] dResults = dResultsDR.getDisplacement();
                if (dResults != null) {
                    final int y2 = finalBottomLine(dResults);
                    final int y1 = finalBottomLine(results);
                    result = y2 / (double) y1;
                }
            }
        }

        return result;
    }

    private static int finalBottomLine(final double[][][] data) {
        for (int y = data[0].length - 1; y >= 0; y--) {
            for (int x = 0; x < data.length; x++) {
                if (data[x][y] != null) {
                    return y;
                }
            }
        }
        return 1;
    }

    public static void serializeTaskToConfig(final TaskContainer tc, final File out) throws IOException {
        final Config config = new Config();
        final int roundCount = getMaxRoundCount(tc);
        // input
        final Object input = tc.getInput();
        if (input instanceof File) {
            final File f = (File) input;
            config.put(ConfigLoader.CONFIG_INPUT, f.getAbsolutePath());
        } else if (input instanceof List) {
            @SuppressWarnings("unchecked")
            final List<File> l = (List<File>) input;
            final StringBuilder sb = new StringBuilder();
            for (File f : l) {
                sb.append(f.getAbsolutePath());
                sb.append(ConfigLoader.CONFIG_SEPARATOR_ARRAY);
            }
            sb.setLength(sb.length() - ConfigLoader.CONFIG_SEPARATOR_ARRAY.length());
            config.put(ConfigLoader.CONFIG_INPUT, sb.toString());
        } else {
            throw new IllegalArgumentException("Unsupported type of input.");
        }
        // rois, deformation limits, subsetSizes        
        Set<AbstractROI> rois, prevRoi = null;
        Map<AbstractROI, Integer> fs, prevFs = null;
        Map<AbstractROI, double[]> limits, prevLimits = null;
        final StringBuilder sb = new StringBuilder();
        for (int round = 0; round < roundCount; round++) {
            rois = tc.getRois(round);
            fs = tc.getSubsetSizes(round);
            limits = tc.getDeformationLimits(round);
            if (rois != prevRoi || fs != prevFs || limits != prevLimits) {
                sb.setLength(0);
                for (AbstractROI roi : rois) {
                    sb.append(roi.toString());
                    sb.append(ConfigLoader.CONFIG_SEPARATOR_ROI);
                    sb.append(toString(tc.getDeformationLimits(round, roi)));
                    sb.append(ConfigLoader.CONFIG_SEPARATOR_ROI);
                    sb.append(Integer.toString(tc.getSubsetSize(round, roi)));
                    sb.append(ConfigLoader.CONFIG_SEPARATOR);
                }
                if (sb.length() > ConfigLoader.CONFIG_SEPARATOR.length()) {
                    sb.setLength(sb.length() - ConfigLoader.CONFIG_SEPARATOR.length());
                }

                config.put(ConfigLoader.CONFIG_ROIS.concat(Integer.toString(round)), sb.toString());
                prevRoi = rois;
            }
        }
        // parameters
        Object val;
        for (TaskParameter tp : TaskParameter.values()) {
            val = tc.getParameter(tp);
            if (val instanceof int[]) {
                config.put(ConfigLoader.CONFIG_PARAMETERS.concat(tp.name()), toString((int[]) val));
            } else if (val instanceof double[]) {
                config.put(ConfigLoader.CONFIG_PARAMETERS.concat(tp.name()), toString((double[]) val));
            } else if (val != null) {
                config.put(ConfigLoader.CONFIG_PARAMETERS.concat(tp.name()), val.toString());
            }
        }

        config.save(out);

    }

    private static String toString(final double[] data) {
        final StringBuilder sb = new StringBuilder();

        if (data != null) {
            for (double d : data) {
                sb.append(" ");
                sb.append(d);
                sb.append(ConfigLoader.CONFIG_SEPARATOR_ARRAY);
            }
            sb.setLength(sb.length() - ConfigLoader.CONFIG_SEPARATOR_ARRAY.length());
        } else {
            sb.append(ConfigLoader.CONFIG_EMPTY);
        }

        return sb.toString();
    }

    private static String toString(final int[] data) {
        final StringBuilder sb = new StringBuilder();

        if (data != null) {
            for (int d : data) {
                sb.append(" ");
                sb.append(d);
                sb.append(ConfigLoader.CONFIG_SEPARATOR_ARRAY);
            }
            sb.setLength(sb.length() - ConfigLoader.CONFIG_SEPARATOR_ARRAY.length());
        } else {
            sb.append(ConfigLoader.CONFIG_EMPTY);
        }

        return sb.toString();
    }

    public static void serializeTaskToBinary(final TaskContainer tc, final File target) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            out.writeObject(tc);
            out.flush();
            out.reset();
        }
    }

    public static void checkTaskValidity(final TaskContainer tc) throws ComputationException {
        final Object in = tc.getParameter(TaskParameter.IN);
        if (in == null) {
            throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "No input.");
        }
        final Object roundData = tc.getParameter(TaskParameter.ROUND_LIMITS);
        if (roundData == null) {
            tc.setParameter(TaskParameter.ROUND_LIMITS, new int[]{0, tc.getImages().size() - 1});
        } else {
            final int[] limit = (int[]) roundData;
            if ((limit.length != 2) || (limit[0] > limit[1])) {
                throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Illegal limits for enabled rounds.");
            }
        }
        final int roundCount = TaskContainerUtils.getRounds(tc).size();
        if (roundCount < 1) {
            throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "No rounds for computation.");
        }
        final Object fs = tc.getParameter(TaskParameter.SUBSET_SIZE);
        if (fs == null) {
            tc.setParameter(TaskParameter.SUBSET_SIZE, TaskDefaultValues.DEFAULT_SUBSET_SIZE);
        }
        final Object dl = tc.getParameter(TaskParameter.DEFORMATION_LIMITS);
        if (dl == null) {
            if (tc.getParameter(TaskParameter.DEFORMATION_ORDER) == DeformationOrder.ZERO) {
                tc.setParameter(TaskParameter.DEFORMATION_LIMITS, TaskDefaultValues.DEFAULT_DEFORMATION_LIMITS_ZERO);
            } else {
                tc.setParameter(TaskParameter.DEFORMATION_LIMITS, TaskDefaultValues.DEFAULT_DEFORMATION_LIMITS_FIRST);
                tc.setParameter(TaskParameter.DEFORMATION_ORDER, TaskDefaultValues.DEFAULT_DEFORMATION_ORDER);
            }
        } else {
            final DeformationOrder limitsDegree = DeformationUtils.getOrderFromLimits((double[]) dl);
            final DeformationOrder taskDegree = (DeformationOrder) tc.getParameter(TaskParameter.DEFORMATION_ORDER);
            if (taskDegree != limitsDegree) {
                tc.setParameter(TaskParameter.DEFORMATION_ORDER, limitsDegree);
            }
        }
        Image img;
        Set<AbstractROI> rois;
        for (int round : TaskContainerUtils.getRounds(tc).keySet()) {
            img = tc.getImage(round);
            if (img == null) {
                throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "NULL image found.");
            }
            rois = tc.getRois(round);
            if (rois == null || rois.isEmpty()) {
                tc.addRoi(round, new RectangleROI(0, 0, img.getWidth() - 1, img.getHeight() - 1));
            }
        }
        final Object ts = tc.getParameter(TaskParameter.TASK_SPLIT_METHOD);
        if (ts == null) {
            tc.setParameter(TaskParameter.TASK_SPLIT_METHOD, TaskDefaultValues.DEFAULT_TASK_SPLIT_METHOD);
        }
        final Object tsp = tc.getParameter(TaskParameter.TASK_SPLIT_PARAM);
        if (tsp == null) {
            tc.setParameter(TaskParameter.TASK_SPLIT_PARAM, TaskDefaultValues.DEFAULT_TASK_SPLIT_PARAMETER);
        }
        final Object subsetGenMode = tc.getParameter(TaskParameter.SUBSET_GENERATOR_METHOD);
        if (subsetGenMode == null) {
            tc.setParameter(TaskParameter.SUBSET_GENERATOR_METHOD, TaskDefaultValues.DEFAULT_SUBSET_GENERATOR);
        }
        final Object subsetGenModeParam = tc.getParameter(TaskParameter.SUBSET_GENERATOR_PARAM);
        if (subsetGenModeParam == null) {
            tc.setParameter(TaskParameter.SUBSET_GENERATOR_PARAM, TaskDefaultValues.DEFAULT_SUBSET_SPACING);
        }
        final Object interpolation = tc.getParameter(TaskParameter.INTERPOLATION);
        if (interpolation == null) {
            tc.setParameter(TaskParameter.INTERPOLATION, TaskDefaultValues.DEFAULT_INTERPOLATION);
        }
        final Object strainEstimation = tc.getParameter(TaskParameter.STRAIN_ESTIMATION_METHOD);
        if (strainEstimation == null) {
            tc.setParameter(TaskParameter.STRAIN_ESTIMATION_METHOD, TaskDefaultValues.DEFAULT_STRAIN_ESTIMATION_METHOD);
        }
        final Object strainEstimationParam = tc.getParameter(TaskParameter.STRAIN_ESTIMATION_PARAM);
        if (strainEstimationParam == null) {
            tc.setParameter(TaskParameter.STRAIN_ESTIMATION_PARAM, TaskDefaultValues.DEFAULT_STRAIN_ESTIMATION_PARAMETER);
        }
        final Object displacementCalculator = tc.getParameter(TaskParameter.DISPLACEMENT_CALCULATION_METHOD);
        if (displacementCalculator == null) {
            tc.setParameter(TaskParameter.DISPLACEMENT_CALCULATION_METHOD, TaskDefaultValues.DEFAULT_DISPLACEMENT_CALCULATION_METHOD);
            tc.setParameter(TaskParameter.DISPLACEMENT_CALCULATION_PARAM, TaskDefaultValues.DEFAULT_DISPLACEMENT_CALCULATION_PARAM);
        }
        final Object ratio = tc.getParameter(TaskParameter.MM_TO_PX_RATIO);
        if (ratio == null) {
            tc.setParameter(TaskParameter.MM_TO_PX_RATIO, TaskDefaultValues.DEFAULT_MM_TO_PX_RATIO);
        }
        final Object quality = tc.getParameter(TaskParameter.RESULT_QUALITY);
        if (quality == null) {
            tc.setParameter(TaskParameter.RESULT_QUALITY, TaskDefaultValues.DEFAULT_RESULT_QUALITY);
        }
        final Object correlation = tc.getParameter(TaskParameter.SOLVER);
        if (correlation == null) {
            tc.setParameter(TaskParameter.SOLVER, TaskDefaultValues.DEFAULT_SOLVER);
        }
        final Object kernel = tc.getParameter(TaskParameter.KERNEL);
        if (kernel == null) {
            final SolverType solver = (SolverType) tc.getParameter(TaskParameter.SOLVER);
            tc.setParameter(TaskParameter.KERNEL, KernelPerformanceManager.getInstance().getBestKernel(solver.supportsWeighedCorrelation()));
        }
        final Object filterSize = tc.getParameter(TaskParameter.FILTER_KERNEL_SIZE);
        if (filterSize == null) {
            tc.setParameter(TaskParameter.FILTER_KERNEL_SIZE, TaskDefaultValues.DEFAULT_FILTER_KERNEL_SIZE);
        }
        final Object weight = tc.getParameter(TaskParameter.CORRELATION_WEIGHT);
        if (weight == null) {
            tc.setParameter(TaskParameter.CORRELATION_WEIGHT, TaskDefaultValues.DEFAULT_CORRELATION_WEIGHT);
        }
        final Object fps = tc.getParameter(TaskParameter.FPS);
        if (fps == null) {
            tc.setParameter(TaskParameter.FPS, TaskDefaultValues.DEFAULT_FPS);
        }
    }

    public static int computeCorrelationWeight(final int subsetSize, final double correlationWeightCoefficient) {
        final int result = (int) Math.round(subsetSize * correlationWeightCoefficient * (3.2));
        return result;
    }

}

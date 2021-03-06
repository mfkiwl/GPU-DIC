/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.complextask;

import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.Coordinates;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.roi.CircularROI;
import cz.tul.dic.data.roi.AbstractROI;
import cz.tul.dic.data.roi.RectangleROI;
import cz.tul.dic.data.task.Hint;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.data.result.CorrelationResult;
import cz.tul.dic.data.subset.AbstractSubset;
import cz.tul.dic.data.subset.generator.AbstractSubsetGenerator;
import cz.tul.dic.engine.cluster.Analyzer1D;
import cz.tul.dic.engine.solvers.SolverType;
import cz.tul.dic.data.subset.generator.SubsetGenerator;
import cz.tul.pj.journal.Journal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Petr Jecmen
 */
public class CircleROIManager extends AbstractROIManager {

    public static final float LIMIT_RESULT_QUALITY = 0.5f;
    private static final double ROOT_TWO = Math.sqrt(2);
    private static final double[] DEFAULT_DEFORMATION_LIMITS = new double[]{-1, 1, 0.5, -5, 10, 0.5};
    private static final int MIN_SUBSET_COUNT = 3;
    private static final int MAX_SHIFT_DIFFERENCE = 3;
    private CircularROI topLeft, topRight, bottomLeft, bottomRight;
    private double shiftTop, shiftBottom;

    private CircleROIManager(TaskContainer tc, final int initialRound) throws ComputationException {
        super(tc);

        tc.setParameter(TaskParameter.SUBSET_GENERATOR_METHOD, SubsetGenerator.EQUAL);
        tc.setParameter(TaskParameter.SUBSET_GENERATOR_PARAM, 1);
        tc.setParameter(TaskParameter.SOLVER, SolverType.BRUTE_FORCE);
        tc.addHint(Hint.NO_STRAIN);
        tc.addHint(Hint.NO_CUMULATIVE);
        tc.addHint(Hint.NO_STATS);
        tc.clearResultData();

        final List<CircularROI> cRois = new ArrayList<>(4);
        if (tc.getRois(initialRound) != null) {
            for (AbstractROI r : tc.getRois(initialRound)) {
                if (r instanceof CircularROI) {
                    cRois.add((CircularROI) r);
                } else if (!(r instanceof RectangleROI)) {
                    throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Unsupported type of ROI - " + r.getClass());
                }
            }
        }

        if (cRois.size() != 4) {
            throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "4 circular ROIs needed.");
        }

        Collections.sort(cRois, new RoiSorter());
        topLeft = cRois.get(0);
        topRight = cRois.get(1);
        bottomLeft = cRois.get(2);
        bottomRight = cRois.get(3);
        
        Journal.addDataEntry(cRois, "Circle ROI manager initialized.");

        defLimits = DEFAULT_DEFORMATION_LIMITS;
        setROIs(initialRound);
    }

    public static CircleROIManager prepareManager(final TaskContainer tc, final int initialRound) throws ComputationException {
        final TaskContainer tcC = new TaskContainer(tc);

        tcC.setROIs(initialRound, tc.getRois(initialRound));

        return new CircleROIManager(tcC, initialRound);
    }

    @Override
    public void generateNextRound(int round, int nextRound) throws ComputationException {
        // find new position of Circle ROIs
        //// determine shifts of circle ROIs from previous round
        final double shift0 = determineROIShift(round, topLeft);
        final double shift1 = determineROIShift(round, topRight);
        final double shift2 = determineROIShift(round, bottomLeft);
        final double shift3 = determineROIShift(round, bottomRight);
        Journal.addEntry("Jaw shifts computed","TOP: {0}, {1}; BOTTOM: {2}, {3}", shift0, shift1, shift2, shift3);
        //// check if left equals right
        if (Math.abs(shift2 - shift3) > MAX_SHIFT_DIFFERENCE) {
            Journal.addEntry("Warning", "Detected fixture shift mismatch for lower fixtures - {0} vs {1}.", shift2, shift3);
        }
        if (Math.abs(shift1 - shift0) > MAX_SHIFT_DIFFERENCE) {
            Journal.addEntry("Warning", "Detected fixture shift mismatch for upper fixtures - {0} vs {1}.", shift0, shift1);
        }
        // generate new Circle ROIs
        topLeft = new CircularROI(topLeft.getCenterX(), topLeft.getCenterY() + shift0, topLeft.getRadius());
        topRight = new CircularROI(topRight.getCenterX(), topRight.getCenterY() + shift1, topRight.getRadius());
        bottomLeft = new CircularROI(bottomLeft.getCenterX(), bottomLeft.getCenterY() + shift2, bottomLeft.getRadius());
        bottomRight = new CircularROI(bottomRight.getCenterX(), bottomRight.getCenterY() + shift3, bottomRight.getRadius());

        if (shift0 < 0 || shift1 < 0) {
            shiftTop = Math.min(shift0, shift1);
        } else {
            shiftTop = Math.max(shift0, shift1);
        }
        if (shift2 < 0 || shift3 < 0) {
            shiftBottom = Math.min(shift2, shift3);
        } else {
            shiftBottom = Math.max(shift2, shift3);
        }

        setROIs(nextRound);
    }

    private double determineROIShift(final int round, final AbstractROI roi) {
        final Analyzer1D analyzer = new Analyzer1D();
        analyzer.setPrecision(PRECISION);

        for (CorrelationResult cr : task.getResult(round, round + 1).getCorrelations().get(roi)) {
            if (cr != null && cr.getQuality() >= LIMIT_RESULT_QUALITY) {
                analyzer.addValue(cr.getDeformation()[Coordinates.Y]);
            }
        }

        return analyzer.findMajorValue();
    }

    private void setROIs(final int round) throws ComputationException {
        final HashSet<AbstractROI> rois = new HashSet<>(4);
        rois.add(topLeft);
        rois.add(topRight);
        rois.add(bottomLeft);
        rois.add(bottomRight);

        task.setROIs(round, rois);

        CircularROI cr;
        double r;
        for (AbstractROI roi : rois) {
            cr = (CircularROI) roi;
            r = cr.getRadius();
            task.addSubsetSize(round, roi, (int) ((r * ROOT_TWO - 1) / 2.0));
            task.setDeformationLimits(round, roi, defLimits);
        }

        final AbstractSubsetGenerator generator = AbstractSubsetGenerator.initGenerator((SubsetGenerator) task.getParameter(TaskParameter.SUBSET_GENERATOR_METHOD));
        Map<AbstractROI, List<AbstractSubset>> subsets;

        boolean sizeAdjusted;
        do {
            subsets = generator.generateSubsets(task, round);
            sizeAdjusted = false;

            for (AbstractROI roi : rois) {
                if (subsets.get(roi).size() < MIN_SUBSET_COUNT) {
                    task.addSubsetSize(round, roi, task.getSubsetSize(round, roi) - 1);
                    sizeAdjusted = true;
                }
            }
        } while (sizeAdjusted);
    }

    public double getShiftTop() {
        return shiftTop;
    }

    public double getShiftBottom() {
        return shiftBottom;
    }

    public Set<AbstractROI> getBottomRois() {
        final Set<AbstractROI> result = new HashSet<>(2);
        result.add(bottomLeft);
        result.add(bottomRight);
        return result;
    }

    public boolean hasMoved() {
        return haveMoved(shiftBottom, shiftBottom);
    }

    public void increaseLimits(final int round) throws ComputationException {
        final double[] oldLimits = defLimits;
        defLimits = new double[oldLimits.length];
        System.arraycopy(oldLimits, 0, defLimits, 0, oldLimits.length);

        final long[] stepCounts = DeformationUtils.generateDeformationCounts(defLimits);
        double mod = stepCounts[0] / 4 * defLimits[2];
        defLimits[0] -= mod;
        defLimits[1] += mod;
        mod = stepCounts[1] / 4 * defLimits[5];
        defLimits[3] -= mod;
        defLimits[4] += mod;

        setROIs(round);
    }

}

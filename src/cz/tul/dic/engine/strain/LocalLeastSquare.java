/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.engine.strain;

import cz.tul.dic.Utils;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.debug.DebugControl;
import cz.tul.dic.debug.Stats;
import cz.tul.dic.data.result.Result;
import cz.tul.dic.data.result.StrainResult;
import cz.tul.dic.output.Direction;
import cz.tul.dic.output.NameGenerator;
import cz.tul.pj.journal.Journal;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

public class LocalLeastSquare extends StrainEstimator {

    private static final int INDEX_A0 = 0;
    private static final int INDEX_A1 = 1;
    private static final int INDEX_A2 = 2;
    private static final int INDEX_B0 = 3;
    private static final int INDEX_B1 = 4;
    private static final int INDEX_B2 = 5;
    private static final int INDEX_ERR_U = 6;
    private static final int INDEX_ERR_V = 7;
    private static final double COEFF_ADJUST = 100;
    private boolean stop;

    public LocalLeastSquare() {
        super();
    }

    @Override
    public void estimateStrain(TaskContainer tc, int roundFrom, int roundTo) {
        stop = false;
        if (roundFrom >= roundTo) {
            throw new IllegalArgumentException("Source round must be lower than target round.");
        }

        final Result subResult = tc.getResult(roundFrom, roundTo);
        final double[][][] displacement = subResult.getDisplacementResult().getDisplacement();
        if (displacement != null) {
            final int width = displacement.length;
            final int height = displacement[0].length;

            final double mm = (double) tc.getParameter(TaskParameter.STRAIN_ESTIMATION_PARAM);
            final double mmToPx = (double) tc.getParameter(TaskParameter.MM_TO_PX_RATIO);
            final int windowSize = (int) Math.ceil(mm * mmToPx);

            final List<ExecutionUnit> l = new ArrayList<>(width * height);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (stop) {
                        return;
                    }

                    if (displacement[x][y] != null) {
                        l.add(new ExecutionUnit(x, y, displacement, windowSize));
                    }
                }
            }

            final double[][][] result = new double[width][height][];
            final double[][] resultQualityU = Utils.generateNaNarray(width, height);
            final double[][] resultQualityV = Utils.generateNaNarray(width, height);
            try {
                final List<Future<ExecutionUnit>> results = exec.invokeAll(l);

                ExecutionUnit eu;
                for (Future<ExecutionUnit> f : results) {
                    eu = f.get();
                    result[eu.getX()][eu.getY()] = eu.getResult();
                    resultQualityU[eu.getX()][eu.getY()] = eu.getErrors()[0];
                    resultQualityV[eu.getX()][eu.getY()] = eu.getErrors()[1];
                }
            } catch (InterruptedException | ExecutionException ex) {
                Journal.addDataEntry(ex, "Error wcomputing strain using LLS.");
            }

            if (DebugControl.isDebugMode()) {
                Stats.getInstance().drawRegressionQualities(
                        tc.getImage(roundTo), resultQualityU, resultQualityV,
                        NameGenerator.generateRegressionQualityMap(tc, roundTo, Direction.EXX),
                        NameGenerator.generateRegressionQualityMap(tc, roundTo, Direction.EYY));
            }

            tc.setResult(roundFrom, roundTo, new Result(subResult, new StrainResult(result, resultQualityU, resultQualityV)));
        }
    }

    private static double[] computeCoeffs(final double[][][] data, final int x, final int y, final int radius) {
        final List<double[]> xU = new LinkedList<>();
        final List<Double> yU = new LinkedList<>();
        final List<double[]> xV = new LinkedList<>();
        final List<Double> yV = new LinkedList<>();

        final int width = data.length;
        final int height = data[x].length;
        for (int i = x - radius; i <= x + radius; i++) {
            for (int j = y - radius; j <= y + radius; j++) {
                if (areIndexesValid(i, j, width, height, data)) {
                    xU.add(new double[]{1, i - x, j - y});
                    yU.add(data[i][j][0]);
                    xV.add(new double[]{1, i - x, j - y});
                    yV.add(data[i][j][1]);
                }
            }
        }

        final double[] result;
        if (xU.size() > 3) {
            result = new double[8];
            try {
                final OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
                regression.setNoIntercept(true);

                double[] dataY = new double[yU.size()];
                for (int i = 0; i < dataY.length; i++) {
                    dataY[i] = yU.get(i);
                }
                double[][] dataX = xU.toArray(new double[][]{});

                regression.newSampleData(dataY, dataX);
                double[] beta = regression.estimateRegressionParameters();
                System.arraycopy(beta, 0, result, 0, 3);
                result[INDEX_ERR_U] = regression.estimateRegressionStandardError();

                dataY = new double[yV.size()];
                for (int i = 0; i < dataY.length; i++) {
                    dataY[i] = yV.get(i);
                }
                dataX = xV.toArray(new double[][]{});

                regression.newSampleData(dataY, dataX);
                beta = regression.estimateRegressionParameters();
                System.arraycopy(beta, 0, result, 3, 3);
                result[INDEX_ERR_V] = regression.estimateRegressionStandardError();
            } catch (MathIllegalArgumentException ex) {
                // singular matrix, let solution be zeroes
            }
        } else {
            result = null;
        }

        return result;
    }

    private static boolean areIndexesValid(int i, int j, final int width, final int height, final double[][][] data) {
        return i >= 0 && j >= 0 && i < width && j < height && data[i][j] != null;
    }

    private static double[] computeStrains(final double[] coeffs) {
        final double[] result = new double[3];

        result[StrainResult.E_XX] = coeffs[INDEX_A1] * COEFF_ADJUST;
        result[StrainResult.E_YY] = coeffs[INDEX_B2] * COEFF_ADJUST;
        result[StrainResult.E_XY] = 0.5 * (coeffs[INDEX_B1] + coeffs[INDEX_A2]) * COEFF_ADJUST;

        return result;
    }

    @Override
    public void stop() {
        stop = true;
    }

    private static class ExecutionUnit implements Callable<ExecutionUnit> {

        private final int x, y, ws;
        private final double[][][] data;
        private double[] result, errors;

        public ExecutionUnit(int x, int y, double[][][] data, final int ws) {
            this.x = x;
            this.y = y;
            this.ws = ws;
            this.data = data;
        }

        @Override
        public ExecutionUnit call() throws Exception {
            final double[] coeffs = computeCoeffs(data, x, y, (int) Math.ceil(ws / 2.0));
            if (coeffs != null) {
                result = computeStrains(coeffs);
                errors = new double[]{coeffs[INDEX_ERR_U], coeffs[INDEX_ERR_V]};
            } else {
                errors = new double[]{1, 1};
            }
            return this;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public double[] getResult() {
            return result;
        }

        public double[] getErrors() {
            return errors;
        }
    }

}

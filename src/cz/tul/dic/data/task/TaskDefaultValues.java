/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.data.task;

import cz.tul.dic.data.deformation.DeformationOrder;
import cz.tul.dic.data.task.splitter.TaskSplitMethod;
import cz.tul.dic.engine.displacement.DisplacementCalculation;
import cz.tul.dic.data.Interpolation;
import cz.tul.dic.engine.solvers.SolverType;
import cz.tul.dic.engine.strain.StrainEstimationMethod;
import cz.tul.dic.data.subset.generator.SubsetGenerator;

/**
 *
 * @author Petr Ječmen
 */
public final class TaskDefaultValues {

    public static final SolverType DEFAULT_SOLVER = SolverType.NEWTON_RHAPSON_CENTRAL;
    public static final double DEFAULT_CORRELATION_WEIGHT = 0.75;
    public static final DisplacementCalculation DEFAULT_DISPLACEMENT_CALCULATION_METHOD = DisplacementCalculation.MAX_WEIGHTED_AVERAGE;
    public static final int DEFAULT_DISPLACEMENT_CALCULATION_PARAM = 2000;
    public static final int DEFAULT_FILTER_KERNEL_SIZE = 5;
    public static final int DEFAULT_FPS = 5000;
    public static final DeformationOrder DEFAULT_DEFORMATION_ORDER = DeformationOrder.FIRST;
    public static final double[] DEFAULT_DEFORMATION_LIMITS_ZERO = new double[]{-10.0, 10.0, 0.01, -10, 10, 0.01};
    public static final double[] DEFAULT_DEFORMATION_LIMITS_FIRST = new double[]{
        -10.0, 10.0, 0.01, -10, 10, 0.01,
        -0.25, 0.25, 0.05, -0.25, 0.25, 0.05, -0.25, 0.25, 0.05, -0.25, 0.25, 0.05};
    public static final SubsetGenerator DEFAULT_SUBSET_GENERATOR = SubsetGenerator.EQUAL;
    public static final int DEFAULT_SUBSET_SPACING = 1;
    public static final int DEFAULT_SUBSET_SIZE = 10;
    public static final Interpolation DEFAULT_INTERPOLATION = Interpolation.BICUBIC;
    public static final double DEFAULT_MM_TO_PX_RATIO = 1;    
    public static final double DEFAULT_RESULT_QUALITY = 0.25;
    public static final StrainEstimationMethod DEFAULT_STRAIN_ESTIMATION_METHOD = StrainEstimationMethod.LOCAL_LEAST_SQUARES;
    public static final double DEFAULT_STRAIN_ESTIMATION_PARAMETER = 20;
    public static final TaskSplitMethod DEFAULT_TASK_SPLIT_METHOD = TaskSplitMethod.DYNAMIC;
    public static final int DEFAULT_TASK_SPLIT_PARAMETER = 1000;

    private TaskDefaultValues() {
    }
}

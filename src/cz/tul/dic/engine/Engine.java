/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.engine;

import cz.tul.dic.engine.platform.PlatformManager;
import cz.tul.dic.engine.platform.Platform;
import cz.tul.dic.data.task.FullTask;
import cz.tul.dic.engine.solvers.AbstractTaskSolver;
import cz.tul.dic.ComputationException;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.subset.AbstractSubset;
import cz.tul.dic.data.roi.AbstractROI;
import cz.tul.dic.data.task.Hint;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.data.task.splitter.TaskSplitMethod;
import cz.tul.dic.debug.DebugControl;
import cz.tul.dic.debug.Stats;
import cz.tul.dic.engine.displacement.DisplacementCalculator;
import cz.tul.dic.data.Interpolation;
import cz.tul.dic.engine.solvers.SolverType;
import cz.tul.dic.data.result.CorrelationResult;
import cz.tul.dic.data.result.DisplacementResult;
import cz.tul.dic.data.result.Result;
import cz.tul.dic.data.subset.generator.AbstractSubsetGenerator;
import cz.tul.dic.engine.strain.StrainEstimator;
import cz.tul.dic.engine.strain.StrainEstimationMethod;
import cz.tul.dic.data.subset.generator.SubsetGenerator;
import cz.tul.dic.output.NameGenerator;
import cz.tul.pj.journal.Journal;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Ječmen
 */
public final class Engine extends Observable implements Observer {

    private static final Engine INSTANCE;
    private final ExecutorService exec;
    private Platform platform;
    private StrainEstimator strain;
    private AbstractTaskSolver solver;
    private boolean stopEngine;

    static {
        INSTANCE = new Engine();
    }

    private Engine() {
        super();
        exec = Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() - 1);        
    }

    public static Engine getInstance() {
        return INSTANCE;
    }

    public void computeTask(final TaskContainer task) throws ComputationException {
        Journal.addDataEntry(task, "Computing task");
        Journal.createSubEntry();

        stopEngine = false;
        setChanged();
        notifyObservers(0);

        task.clearResultData();
        TaskContainerUtils.checkTaskValidity(task);

        if (platform != null) {
            platform.release();
        }
        platform = PlatformManager.getInstance().initPlatform();
        platform.getMemoryManager().assignTask(task);        

        strain = StrainEstimator.initStrainEstimator((StrainEstimationMethod) task.getParameter(TaskParameter.STRAIN_ESTIMATION_METHOD));
        final Set<Future<Void>> futures = new HashSet<>();

        int r, nextR, baseR = -1;
        for (Map.Entry<Integer, Integer> e : TaskContainerUtils.getRounds(task).entrySet()) {
            if (stopEngine) {
                endTask();
                return;
            }

            r = e.getKey();
            nextR = e.getValue();

            setChanged();
            notifyObservers(r);

            computeRound(task, r, nextR);

            if (baseR == -1) {
                baseR = r;
            } else {
                futures.add(exec.submit(new OverlapComputation(task, baseR, nextR, strain)));
            }
        }

        Stats.getInstance().dumpDeformationsStatisticsUsage();
        Stats.getInstance().dumpDeformationsStatisticsPerQuality();

        try {
            setChanged();
            notifyObservers(StrainEstimator.class);
            for (Future f : futures) {
                f.get();
            }
        } catch (InterruptedException | ExecutionException ex) {
            Journal.addDataEntry(ex, "Error waiting for Strain estimation.");
        }

        endTask();

        try {
            TaskContainerUtils.serializeTaskToBinary(task, new File(NameGenerator.generateBinary(task)));
        } catch (IOException ex) {
            Journal.addDataEntry(ex, "Task serialization to binary failed.");
        }

        Journal.closeSubEntry();
    }

    public void computeRound(final TaskContainer task, final int roundFrom, final int roundTo) throws ComputationException {
        stopEngine = false;

        final long time = System.currentTimeMillis();

        Journal.addEntry("Computing round", "Round {0}:{1}.", roundFrom, roundTo);
        Journal.createSubEntry();
        final Set<Hint> hints = task.getHints();
        if (hints.contains(Hint.NO_STATS)) {
            DebugControl.pauseDebugMode();
        } else {
            DebugControl.resumeDebugMode();
        }
        Stats.getInstance().setTaskContainer(task);

        if (platform == null) {
            platform = PlatformManager.getInstance().initPlatform();
            platform.getMemoryManager().assignTask(task);
        }
        final KernelInfo backup = (KernelInfo) task.getParameter(TaskParameter.KERNEL);
        task.setParameter(TaskParameter.KERNEL, platform.getPlatformDefinition().getKernelInfo());
        
        setChanged();
        notifyObservers(TaskContainerUtils.class);
        TaskContainerUtils.checkTaskValidity(task);

        // prepare correlation calculator
        solver = AbstractTaskSolver.initSolver((SolverType) task.getParameter(TaskParameter.SOLVER), platform);
        solver.addObserver(this);        
        solver.setInterpolation((Interpolation) task.getParameter(TaskParameter.INTERPOLATION));
        final TaskSplitMethod taskSplit = (TaskSplitMethod) task.getParameter(TaskParameter.TASK_SPLIT_METHOD);
        final Object taskSplitValue = task.getParameter(TaskParameter.TASK_SPLIT_PARAM);
        solver.setTaskSplitVariant(taskSplit, taskSplitValue);

        strain = StrainEstimator.initStrainEstimator((StrainEstimationMethod) task.getParameter(TaskParameter.STRAIN_ESTIMATION_METHOD));

        final int filterSize = (int) task.getParameter(TaskParameter.FILTER_KERNEL_SIZE);
        final Image in = task.getImage(roundFrom);
        in.filter(filterSize);
        final Image out = task.getImage(roundTo);
        out.filter(filterSize);

        // prepare data
        setChanged();
        notifyObservers(SubsetGenerator.class);

        final AbstractSubsetGenerator generator = AbstractSubsetGenerator.initGenerator((SubsetGenerator) task.getParameter(TaskParameter.SUBSET_GENERATOR_METHOD));
        final HashMap<AbstractROI, List<AbstractSubset>> subsets = generator.generateSubsets(task, roundFrom);

        // compute round                
        final HashMap<AbstractROI, List<CorrelationResult>> correlations = new HashMap<>(task.getRois(roundFrom).size());
        List<AbstractSubset> subsetList;
        List<Integer> subsetWeights;
        int subsetSize, correlationWeight;
        for (AbstractROI roi : task.getRois(roundFrom)) {
            if (stopEngine) {
                return;
            }

            subsetList = subsets.get(roi);
            subsetSize = task.getSubsetSize(roundFrom, roi);
            correlationWeight = TaskContainerUtils.computeCorrelationWeight(subsetSize, (double) task.getParameter(TaskParameter.CORRELATION_WEIGHT));
            subsetWeights = Collections.nCopies(subsetList.size(), correlationWeight);

            // compute and store result
            setChanged();
            notifyObservers(AbstractTaskSolver.class);
            correlations.put(
                    roi,
                    solver.solve(new FullTask(
                            in, out,
                            subsetList, subsetWeights,
                            generateDeformations(task.getDeformationLimits(roundFrom, roi), subsets.get(roi).size()))));
        }

        setChanged();
        notifyObservers(DisplacementCalculator.class);
        final DisplacementResult displacement = DisplacementCalculator.computeDisplacement(correlations, subsets, task, roundFrom);

        task.setResult(roundFrom, roundTo, new Result(subsets, correlations, displacement));

        final Future future = exec.submit(new OverlapComputation(task, roundFrom, roundTo, strain));

        if (DebugControl.isDebugMode()) {
            Stats.getInstance().dumpDeformationsStatisticsUsage(roundFrom);
            Stats.getInstance().dumpDeformationsStatisticsPerQuality(roundFrom);
            Stats.getInstance().drawSubsetQualityStatistics(subsets, roundFrom, roundTo);
            Stats.getInstance().drawPointResultStatistics(roundFrom, roundTo);
        }

        try {
            setChanged();
            notifyObservers(StrainEstimator.class);
            future.get();
        } catch (InterruptedException | ExecutionException | NullPointerException ex) {
            Logger.warn(ex, "Error waitng for overlapping computation.");
        }

        setChanged();
        notifyObservers(System.currentTimeMillis() - time);

        solver.deleteObserver(this);
        task.setParameter(TaskParameter.KERNEL, backup);

        Journal.addEntry("Round finished.");
        Journal.closeSubEntry();
    }

    public void endTask() {
        if (solver != null) {
            solver.endTask();
        }
    }

    private static List<double[]> generateDeformations(final double[] limits, final int subsetCount) {
        return Collections.nCopies(subsetCount, limits);
    }

    public void stop() {
        stopEngine = true;
        solver.stop();
        strain.stop();
        exec.shutdownNow();
    }

    @Override
    public void update(final Observable o, final Object arg) {
        if (o instanceof AbstractTaskSolver) {
            setChanged();
            notifyObservers(arg);
        } else {
            Logger.error("Illegal observable notification - " + o.toString());
        }
    }

    public ExecutorService getExecutorService() {
        return exec;
    }

}

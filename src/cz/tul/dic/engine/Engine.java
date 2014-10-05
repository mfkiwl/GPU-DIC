package cz.tul.dic.engine;

import cz.tul.dic.ComputationException;
import cz.tul.dic.Utils;
import cz.tul.dic.data.Facet;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.roi.ROI;
import cz.tul.dic.data.task.Hint;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.data.task.splitter.TaskSplitMethod;
import cz.tul.dic.debug.DebugControl;
import cz.tul.dic.debug.ResultStats;
import cz.tul.dic.engine.displacement.DisplacementCalculator;
import cz.tul.dic.engine.opencl.KernelType;
import cz.tul.dic.engine.opencl.interpolation.Interpolation;
import cz.tul.dic.engine.strain.StrainEstimation;
import cz.tul.dic.generators.facet.FacetGenerator;
import cz.tul.dic.output.Direction;
import cz.tul.dic.output.data.ExportMode;
import cz.tul.dic.output.ExportTask;
import cz.tul.dic.output.ExportUtils;
import cz.tul.dic.output.Exporter;
import cz.tul.dic.output.NameGenerator;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Set;
import javax.imageio.ImageIO;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Ječmen
 */
public class Engine extends Observable {

    private static final Engine instance;
    private final CorrelationCalculator correlation;
    private final StrainEstimation strain;
    private final FineLocalSearch fls;

    static {
        instance = new Engine();
    }

    public static Engine getInstance() {
        return instance;
    }

    private Engine() {
        correlation = new CorrelationCalculator();
        fls = new FineLocalSearch();
        strain = new StrainEstimation();
    }

    public void computeTask(final TaskContainer tc) throws ComputationException, IOException {
        setChanged();
        notifyObservers(0);

        tc.clearResultData();
        TaskContainerUtils.checkTaskValidity(tc);

        final Set<Hint> hints = tc.getHints();
        int r, nextR, currentRound = 0;
        for (Map.Entry<Integer, Integer> e : TaskContainerUtils.getRounds(tc).entrySet()) {
            r = e.getKey();
            nextR = e.getValue();

            computeRound(tc, r, nextR);
            exportRound(tc, r);

            currentRound++;
            setChanged();
            notifyObservers(currentRound);
        }
        
        ResultStats.dumpResultStatistics(tc);

        if (!hints.contains(Hint.NO_STRAIN)) {
            setChanged();
            notifyObservers(StrainEstimation.class);
            strain.computeStrain(tc);
        }

        Exporter.export(tc);
        TaskContainerUtils.serializeTaskToBinary(tc, new File(NameGenerator.generateBinary(tc)));
    }

    private void exportRound(final TaskContainer tc, final int round) throws IOException, ComputationException {
        Iterator<ExportTask> it = tc.getExports().iterator();
        ExportTask et;
        while (it.hasNext()) {
            et = it.next();
            if (et.getMode().equals(ExportMode.MAP) && et.getDataParams()[0] == round && !isStrainExport(et)) {
                Exporter.export(tc, et);
            }
        }
    }

    private boolean isStrainExport(ExportTask et) {
        final Direction dir = et.getDirection();
        return dir == Direction.Eabs || dir == Direction.Exy || dir == Direction.Exx || dir == Direction.Eyy;
    }

    public void computeRound(final TaskContainer tc, final int roundFrom, final int roundTo) throws ComputationException, IOException {
        Logger.trace("Computing round {0}:{1} - {2}.", roundFrom, roundTo, tc);
        final Set<Hint> hints = tc.getHints();
        if (hints.contains(Hint.NO_STATS)) {
            DebugControl.pauseDebugMode();
        } else {
            DebugControl.resumeDebugMode();
        }

        setChanged();
        notifyObservers(TaskContainerUtils.class);
        TaskContainerUtils.checkTaskValidity(tc);

        // prepare parameters
        correlation.setKernel((KernelType) tc.getParameter(TaskParameter.KERNEL));
        correlation.setInterpolation((Interpolation) tc.getParameter(TaskParameter.INTERPOLATION));
        final TaskSplitMethod taskSplit = (TaskSplitMethod) tc.getParameter(TaskParameter.TASK_SPLIT_METHOD);
        final Object taskSplitValue = tc.getParameter(TaskParameter.TASK_SPLIT_PARAM);
        correlation.setTaskSplitVariant(taskSplit);

        // prepare data
        setChanged();
        notifyObservers(FacetGenerator.class);
        final Map<ROI, List<Facet>> facets = FacetGenerator.generateFacets(tc, roundFrom);

        // compute round                
        for (ROI roi : tc.getRois(roundFrom)) {
            // compute and store result
            setChanged();
            notifyObservers(CorrelationCalculator.class);
            tc.setResult(
                    roundFrom, roi,
                    correlation.computeCorrelations(
                            tc.getImage(roundFrom), tc.getImage(roundTo),
                            roi, facets.get(roi),
                            tc.getDeformationLimits(roundFrom, roi),
                            DeformationUtils.getDegreeFromLimits(tc.getDeformationLimits(roundFrom, roi)),
                            tc.getFacetSize(roundFrom, roi), taskSplitValue));
        }
        if (DebugControl.isDebugMode()) {
            ResultStats.dumpResultStatistics(tc, roundFrom);
            dumpResultQualityStatistics(tc, facets, roundFrom, roundTo);
        }

        setChanged();
        notifyObservers(DisplacementCalculator.class);
        DisplacementCalculator.computeDisplacement(tc, roundFrom, roundTo, facets);

        if (!hints.contains(Hint.NO_FINE_SEARCH)) {
            setChanged();
            notifyObservers(FineLocalSearch.class);
            fls.searchForBestPosition(tc, roundFrom, roundTo);
        }

        Logger.debug("Computed round {0}:{1}.", roundFrom, roundTo);
    }

    private void dumpResultQualityStatistics(final TaskContainer tc, final Map<ROI, List<Facet>> allFacets, final int roundFrom, final int roundTo) throws IOException, ComputationException {
        final Map<ROI, List<CorrelationResult>> allResults = tc.getResults(roundFrom);

        final Image img = tc.getImage(roundTo);
        final double[][] resultData = Utils.generateNaNarray(img.getWidth(), img.getHeight());

        List<CorrelationResult> results;
        List<Facet> facets;
        double[] center;
        for (ROI roi : allResults.keySet()) {
            results = allResults.get(roi);
            facets = allFacets.get(roi);

            for (int i = 0; i < results.size(); i++) {
                center = facets.get(i).getCenter();
                if (results.get(i) != null) {
                    resultData[(int) Math.round(center[0])][(int) Math.round(center[1])] = results.get(i).getValue();
                }
            }
        }

        ImageIO.write(ExportUtils.overlayImage(img, ExportUtils.createImageFromMap(resultData, Direction.Dabs)), "BMP", new File(NameGenerator.generateQualityMap(tc, roundTo)));
    }

}

package cz.tul.dic.output;

import cz.tul.dic.data.Facet;
import cz.tul.dic.data.FacetUtils;
import cz.tul.dic.data.roi.ROI;
import cz.tul.dic.data.task.TaskContainer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Jecmen
 */
public class OutputUtils {

    private static final String CONFIG_EXPORTS = "EXPORT_";

    public static Map<String, String> serializeExports(final Set<ExportTask> exports) {
        final Map<String, String> result = new HashMap<>();

        int i = 0;
        for (ExportTask et : exports) {
            result.put(CONFIG_EXPORTS.concat(Integer.toString(i)), et.toString());
            i++;
        }

        return result;
    }

    public static Set<ExportTask> deserializeExports(final Map<String, String> data) {
        final Set<ExportTask> result = new HashSet<>();

        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey().startsWith(CONFIG_EXPORTS)) {
                result.add(ExportTask.generateExportTask(e.getValue()));
            } else {
                Logger.warn("Illegal data found in serrialized exports : {0} - {1}", new Object[]{e.getKey(), e.getValue()});
            }
        }

        return result;
    }

    public static void checkExportValidity(final Set<ExportTask> exports) {
        for (ExportTask et : exports) {
            if (!Exporter.isExportSupported(et)) {
                throw new IllegalArgumentException("Unsupported exeport task - " + et);
            }
        }
    }

    public static boolean isPointInsideROIs(final int x, final int y, final ROI[] rois, final TaskContainer tc, final int round) {
        boolean result = false;
        
        for (ROI roi : rois) {
            if (roi == null) {
                continue;
            }

            if (roi.isPointInside(x, y)) {
                // check facets                
                for (Facet f : tc.getFacets(round, roi)) {
                    if (FacetUtils.isPointInsideFacet(f, x, y)) {
                        result = true;
                        break;
                    }
                }
            }
        }

        return result;
    }

}
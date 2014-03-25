package cz.tul.dic.complextask;

import cz.tul.dic.data.roi.ROI;
import java.util.Comparator;

/**
 *
 * @author Petr Jecmen
 */
public class RoiSorter implements Comparator<ROI> {

    @Override
    public int compare(ROI o1, ROI o2) {
        final int y11 = o1.getY1();
        final int y12 = o1.getY2();
        final int y21 = o2.getY1();
        final int y22 = o2.getY2();

        final int result;
        if (y11 >= y21 && y11 <= y22 || y12 >= y21 && y12 <= y22) {
            result = Integer.compare(o1.getX1(), o2.getX1());
        } else {
            result = Integer.compare(y11, y21);
        }        

        return result;
    }

}
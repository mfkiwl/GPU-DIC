/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.data.task.splitter;

import cz.tul.dic.data.task.ComputationTask;
import cz.tul.dic.data.task.FullTask;
import java.util.NoSuchElementException;

public class NoSplit extends AbstractTaskSplitter {

    private boolean hasNextElement;

    public NoSplit(final FullTask task) {
        super(task);
        hasNextElement = true;
    }

    @Override
    public boolean hasNext() {
        return hasNextElement;
    }

    @Override
    public ComputationTask next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        hasNextElement = false;
        return new ComputationTask(image1, image2, subsets, subsetWeights, deformationLimits);
    }

}

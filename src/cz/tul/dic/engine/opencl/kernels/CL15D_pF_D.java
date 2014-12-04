package cz.tul.dic.engine.opencl.kernels;

import com.jogamp.opencl.CLBuffer;
import com.jogamp.opencl.CLEvent;
import com.jogamp.opencl.CLEventList;
import com.jogamp.opencl.CLMemory;
import cz.tul.dic.engine.opencl.WorkSizeManager;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class CL15D_pF_D extends Kernel {

    private static final int ARGUMENT_INDEX_F_INDEX = 11;
    private static final int ARGUMENT_INDEX_D_COUNT = 12;
    private static final int ARGUMENT_INDEX_D_BASE = 13;
    private static final int LWS0_BASE = 32;
    private final WorkSizeManager wsm;
    private boolean stop;

    public CL15D_pF_D() {
        super("CL15D_pF_D");
        wsm = new WorkSizeManager();
    }

    @Override
    void runKernel(final CLMemory<IntBuffer> imgA, final CLMemory<IntBuffer> imgB,
            final CLBuffer<IntBuffer> facetData,
            final CLBuffer<FloatBuffer> facetCenters,
            final CLBuffer<FloatBuffer> deformationLimits, final CLBuffer<IntBuffer> defStepCounts,
            final CLBuffer<FloatBuffer> results,
            final int deformationCount, final int imageWidth,
            final int facetSize, final int facetCount) {
        stop = false;
        final int facetArea = facetSize * facetSize;
        int lws0 = Kernel.roundUp(calculateLws0base(), facetArea);
        lws0 = Math.min(lws0, device.getMaxWorkItemSizes()[0]);

        kernelDIC.rewind();
        kernelDIC.putArgs(imgA, imgB, facetData, facetCenters, deformationLimits, defStepCounts, results)
                .putArg(imageWidth)
                .putArg(deformationCount)
                .putArg(facetSize)
                .putArg(facetCount)
                .putArg(0)
                .putArg(0)
                .putArg(0);
        kernelDIC.rewind();
        // copy data and execute kernel
        wsm.setMaxFacetCount(facetCount);
        wsm.setMaxDeformationCount(deformationCount);
        wsm.reset();
        int globalWorkSize, deformationSubCount;
        long time;
        CLEvent event;
        int currentBaseFacet = 0, currentBaseDeformation;
        int counter = 0;
        CLEventList eventList = new CLEventList(facetCount);
        while (currentBaseFacet < facetCount) {
            currentBaseDeformation = 0;
            kernelDIC.setArg(ARGUMENT_INDEX_F_INDEX, currentBaseFacet);

            while (currentBaseDeformation < deformationCount) {
                if (counter == eventList.capacity()) {
                    eventList = new CLEventList(facetCount);
                    counter = 0;
                }
                if (stop) {
                    return;
                }

                deformationSubCount = Math.min(wsm.getDeformationCount(), deformationCount - currentBaseDeformation);

                globalWorkSize = Kernel.roundUp(lws0, deformationSubCount);

                kernelDIC.setArg(ARGUMENT_INDEX_D_COUNT, deformationSubCount);
                kernelDIC.setArg(ARGUMENT_INDEX_D_BASE, currentBaseDeformation);
                queue.put1DRangeKernel(kernelDIC, 0, globalWorkSize, lws0, eventList);

                queue.putWaitForEvent(eventList, counter, true);
                event = eventList.getEvent(counter);
                time = event.getProfilingInfo(CLEvent.ProfilingCommand.END) - event.getProfilingInfo(CLEvent.ProfilingCommand.START);
                wsm.storeTime(1, deformationSubCount, time);

                currentBaseDeformation += deformationSubCount;
                counter++;
            }

            currentBaseFacet += 1;
        }

        eventList.release();
    }

    private int calculateLws0base() {
//        final IntBuffer val = Buffers.newDirectIntBuffer(5);
//        context.getCL().clGetKernelWorkGroupInfo(kernel.getID(), queue.getDevice().getID(), CLKernelBinding.CL_KERNEL_PREFERRED_WORK_GROUP_SIZE_MULTIPLE, Integer.SIZE, val, null);
//        return val.get(0);
        return LWS0_BASE;
    }

    @Override
    public void stop() {
        stop = true;
    }

}
package cz.tul.dic.engine;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opencl.CLBuffer;
import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLContext;
import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLDevice.Type;
import com.jogamp.opencl.CLErrorHandler;
import com.jogamp.opencl.CLImage2d;
import com.jogamp.opencl.CLImageFormat;
import com.jogamp.opencl.CLKernel;
import com.jogamp.opencl.CLMemory.Mem;
import static com.jogamp.opencl.CLMemory.Mem.READ_ONLY;
import com.jogamp.opencl.CLPlatform;
import com.jogamp.opencl.CLProgram;
import com.jogamp.opencl.CLResource;
import com.jogamp.opencl.llb.CLKernelBinding;
import com.jogamp.opencl.util.Filter;
import cz.tul.dic.data.Coordinates;
import cz.tul.dic.data.Facet;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.engine.opencl.WorkSizeManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 * @author Petr Jecmen
 */
public class Engine {

    private static final Type DEVICE_TYPE = Type.GPU;
    private static final CLImageFormat IMAGE_FORMAT = new CLImageFormat(CLImageFormat.ChannelOrder.RGBA, CLImageFormat.ChannelType.UNSIGNED_INT8);
    private static final String KERNEL_NAME = "CL1D_I_V_LL_MC_D";
    private static final String KERNEL_EXTENSION = ".cl";
    private static final int ARGUMENT_INDEX = 12;
    private final Set<CLResource> clMem;
    private final WorkSizeManager wsm;
    private final CLPlatform platform;
    private final CLContext context;
    private final CLDevice device;

    public Engine(final WorkSizeManager wsm) {
        clMem = new HashSet<>();
        this.wsm = wsm;

        final CLPlatform tmpP = CLPlatform.getDefault(new Filter<CLPlatform>() {

            @Override
            public boolean accept(CLPlatform i) {
                return i.getMaxFlopsDevice(Type.GPU) != null && i.listCLDevices(CLDevice.Type.CPU).length == 0;
            }
        });
        if (tmpP == null) {
            platform = CLPlatform.getDefault();
        } else {
            platform = tmpP;
        }

        final CLDevice tmpD = platform.getMaxFlopsDevice(DEVICE_TYPE);
        if (tmpD == null) {
            device = platform.getMaxFlopsDevice();
        } else {
            device = tmpD;
        }

        context = CLContext.create(device);
        context.addCLErrorHandler(new CLErrorHandler() {

            @Override
            public void onError(String string, ByteBuffer bb, long l) {
                System.err.println("CLError - " + string);
            }
        });

        System.err.println("TODO Support multiple kernels.");
    }

    public void computeTask(final TaskContainer tc) {
        final int roundCount = TaskContainerUtils.getRoundCount(tc);
        final int facetSize = tc.getFacetSize();
        final int facetArea = facetSize * facetSize;

        List<Facet> facets;
        Image img;
        CLImage2d<IntBuffer> imgA, imgB;
        CLBuffer<IntBuffer> facetData;
        CLBuffer<FloatBuffer> facetCenters;
        CLBuffer<FloatBuffer> deformations, results;
        CLProgram program;
        CLKernel kernel;
        float[] roundResult;
        int facetCount;
        for (int round = 0; round < roundCount; round++) {
            // generate data for OpenCL            
            img = tc.getImages().get(round);
            imgA = generateImage(img);
            imgB = generateImage(tc.getImages().get(round + 1));

            facets = tc.getFacets(round);
            facetCount = facets.size();
            facetData = generateFacetData(facets, facetSize);
            facetCenters = generateFacetCenters(facets);

            deformations = generateDeformations(tc.getDeformations());
            final int deformationCount = TaskContainerUtils.getDeformationCount(tc);

            results = context.createFloatBuffer(facetCount * deformationCount, Mem.WRITE_ONLY);
            clMem.add(results);
            // create kernel and parameters            
            final int facetSubCount = wsm.getWorkSize(this.getClass());

            program = context.createProgram(loadAndPrepareKernel(facetSize)).build();
            clMem.add(program);

            kernel = program.createCLKernel(KERNEL_NAME);
            clMem.add(kernel);

            final int lws0base = calculateLws0base(kernel);
            final int lws0;
            if (deformationCount > facetArea) {
                lws0 = EngineMath.roundUp(lws0base, facetArea);
            } else {
                lws0 = EngineMath.roundUp(lws0base, deformationCount);
            }
            final int facetGlobalWorkSize = EngineMath.roundUp(lws0, deformationCount) * facetSubCount;
            final int groupCountPerFacet = deformationCount / lws0;

            kernel.putArgs(imgA, imgB, facetData, facetCenters, deformations, results)
                    .putArg(img.getWidth())
                    .putArg(deformationCount)
                    .putArg(facetSize)
                    .putArg(facetCount)
                    .putArg(groupCountPerFacet)
                    .putArg(facetSubCount)
                    .putArg(0)
                    .rewind();
            // copy data and execute kernel
            final int kernelRoundCount = (int) Math.ceil(facetCount / (double) facetSubCount);
            final CLCommandQueue queue = device.createCommandQueue();
            clMem.add(queue);

            queue.putWriteImage(imgA, false);
            queue.putWriteImage(imgB, false);
            queue.putWriteBuffer(facetData, false);
            queue.putWriteBuffer(facetCenters, false);
            queue.putWriteBuffer(deformations, false);
            for (int j = 0; j < kernelRoundCount; j++) {
                kernel.setArg(ARGUMENT_INDEX, j * facetSubCount);
                queue.put1DRangeKernel(kernel, 0, facetGlobalWorkSize, lws0);
            }
            // copy data back            
            queue.putReadBuffer(results, true);
            queue.finish();
            roundResult = readBuffer(results.getBuffer());
            // pick best values            
            final List<double[]> bestResults = pickBestResults(roundResult, tc, facetCount);
            // store data           
            tc.storeResult(bestResults, round);
            // round memory cleanup
            for (CLResource mem : clMem) {
                mem.release();
            }
        }
    }

    private CLImage2d<IntBuffer> generateImage(final Image image) {
        final int[] imageData = image.toArray();
        final int imageWidth = image.getWidth();
        final IntBuffer imageBuffer = Buffers.newDirectIntBuffer(imageData);
        final CLImage2d<IntBuffer> result = context.createImage2d(imageBuffer, imageWidth, imageData.length / imageWidth, IMAGE_FORMAT, READ_ONLY);
        clMem.add(result);
        return result;
    }

    private CLBuffer<IntBuffer> generateFacetData(final List<Facet> facets, final int facetSize) {
        final int facetArea = facetSize * facetSize;
        final int dataSize = facetArea * Coordinates.DIMENSION;
        final int[] data = new int[facets.size() * dataSize];

        int pointer = 0;
        int[] facetData;
        for (Facet f : facets) {
            facetData = f.getData();
            System.arraycopy(facetData, 0, data, pointer, dataSize);
            pointer += dataSize;
        }

        final CLBuffer<IntBuffer> result = context.createIntBuffer(data.length, Mem.READ_ONLY);
        final IntBuffer resultBuffer = result.getBuffer();
        int index;
        for (int i = 0; i < facetArea; i++) {
            for (int f = 0; f < facets.size(); f++) {
                index = f * dataSize + 2 * i;
                resultBuffer.put(data[index]);
                resultBuffer.put(data[index + 1]);
            }
        }
        resultBuffer.rewind();

        clMem.add(result);
        return result;
    }

    private CLBuffer<FloatBuffer> generateFacetCenters(final List<Facet> facets) {
        final int dataSize = Coordinates.DIMENSION;
        final float[] data = new float[facets.size() * dataSize];

        int pointer = 0;
        float[] centerData;
        for (Facet f : facets) {
            centerData = f.getCenter();
            System.arraycopy(centerData, 0, data, pointer, dataSize);
            pointer += dataSize;
        }

        final CLBuffer<FloatBuffer> result = context.createFloatBuffer(data.length, Mem.READ_ONLY);
        final FloatBuffer buffer = result.getBuffer();
        for (float f : data) {
            buffer.put(f);
        }
        buffer.rewind();

        clMem.add(result);
        return result;
    }

    private CLBuffer<FloatBuffer> generateDeformations(final double[] deformations) {
        final CLBuffer<FloatBuffer> result = context.createFloatBuffer(deformations.length, Mem.READ_ONLY);
        final FloatBuffer buffer = result.getBuffer();
        for (double f : deformations) {
            buffer.put((float) f);
        }
        buffer.rewind();

        clMem.add(result);
        return result;
    }

    private String loadAndPrepareKernel(final int facetSize) {
        String source = null;
        try {
            final InputStream in = WorkSizeManager.class.getResourceAsStream(KERNEL_NAME.concat(KERNEL_EXTENSION));
            final BufferedReader bin = new BufferedReader(new InputStreamReader(in));
            final StringBuilder sb = new StringBuilder();
            while (bin.ready()) {
                sb.append(bin.readLine());
                sb.append("\n");
            }
            source = sb.toString().replaceAll("-1", Integer.toString(facetSize));
        } catch (IOException ex) {
            // should not happen
            ex.printStackTrace(System.err);
        }
        return source;
    }

    private int calculateLws0base(final CLKernel kernel) {
        final IntBuffer val = Buffers.newDirectIntBuffer(2);
        context.getCL().clGetKernelWorkGroupInfo(kernel.getID(), device.getID(), CLKernelBinding.CL_KERNEL_PREFERRED_WORK_GROUP_SIZE_MULTIPLE, Integer.SIZE, val, null);
        return val.get(0);
    }

    private float[] readBuffer(final FloatBuffer buffer) {
        buffer.rewind();
        float[] result = new float[buffer.remaining()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.get(i);
        }
        return result;
    }

    private List<double[]> pickBestResults(final float[] completeResults, final TaskContainer tc, final int facetCount) {
        final List<double[]> result = new ArrayList<>(facetCount);
        
        final int deformationCount = TaskContainerUtils.getDeformationCount(tc);

        float best, val;
        int bestIndex, index;
        for (int facet = 0; facet < facetCount; facet++) {
            best = -Float.MAX_VALUE;
            bestIndex = -1;
            index = facet * deformationCount;
            for (int def = 0; def < deformationCount; def++) {
                val = completeResults[index];
                if (val > best) {
                    best = val;
                    bestIndex = def;
                }
                
                index++;
            }
            result.add(TaskContainerUtils.extractDeformation(tc, bestIndex));
        }

        return result;
    }

}

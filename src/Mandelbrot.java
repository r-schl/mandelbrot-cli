import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.yaml.snakeyaml.Yaml;

import jdk.jfr.Percentage;

public class Mandelbrot {
    private static final String OUT_OF_MEMORY_ERR = "\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<";
    private static final int PROGRESS_BAR_WIDTH = 30;

    private final int NUMTHREADS = Runtime.getRuntime().availableProcessors();
    private final int ESCAPE_RADIUS = 2;
    int width;
    int height;
    double zMinRe;
    double zMinIm;
    double zMaxRe;
    double zMaxIm;
    int nMax;
    int[] data;
    int rowsCompleted = 0;
    double percentageCompleted = 0;
    int finishedThreads = 0;
    long startTime;
    SwingWorker<int[], Integer>[] workers;
    int[] palette;
    int[] gradient;
    int color;
    static boolean isVerbose = false;
    static boolean shouldOpen = false;

    public static void main(String[] args) {

        int k = 0;
        if (args[0].startsWith("-")) {
            k++;
            if (args[0].indexOf('o') != -1)
                shouldOpen = true;
            if (args[0].indexOf('v') != -1)
                isVerbose = true;
        }

        Mandelbrot mand = Mandelbrot.fromConfigFile(args[k]);
        String outputPath = args[k + 1];

        mand.build((percentage) -> { // on progress update
            ///////////////////////
            String progressStr = "";
            while (progressStr.length() < PROGRESS_BAR_WIDTH * (percentage / 100.0D))
                progressStr += "#";
            while (progressStr.length() < PROGRESS_BAR_WIDTH)
                progressStr += " ";
            String str = "|" + progressStr + "| " + (double) Math.round(percentage * 10.0D) / 10.0D + "%";
            str += percentage != 100.0D ? "   [in progress] \r" : "   [done]  ";
            System.out.print(str);
            ///////////////////////
        }, () -> { // on finish
            ///////////////////////
            if (isVerbose) {
                System.out.println("       ");
                System.out.println("> output file: " + outputPath);
                System.out.println("> configurations: ");
                System.out.println("   - picture dimension: " + mand.width + "x" + mand.height);
                System.out.println("   - min complex number: " + mand.zMinRe + (mand.zMinIm > 0.0D ? "+" : "")
                        + mand.zMinIm + "i");
                System.out.println("   - max complex number: " + mand.zMaxRe + (mand.zMaxIm > 0.0D ? "+" : "")
                        + mand.zMaxIm + "i");
                System.out.println("   - max iterations: " + mand.nMax);
                System.out.println("   - set color: " + mand.color);
                System.out.println("   - color gradient: " + Arrays.toString(mand.gradient));
                System.out.println("> build information: ");
                System.out.println(
                        "   - build time: " + (double) (System.currentTimeMillis() - mand.startTime) / 1000.0D + "s");
                long numIterationsTotal = mand.countTotalIterations();
                System.out.println("   - total number of iterations: " + numIterationsTotal);
                System.out.println("   - average number of iterations per pixel: "
                        + (double) Math.round((double) numIterationsTotal / (double) mand.data.length * 100.0D)
                                / 100.0D);
            } else {
                System.out.println("> output: " + outputPath);
            }

            mand.export(outputPath);

            if (shouldOpen) {
                try {
                    Desktop.getDesktop().open(new File(outputPath));
                } catch (IOException err) {
                }
            }
            ///////////////////////
        });
    }

    public static Mandelbrot fromConfigFile(String path) {
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData = null;
        try {
            InputStream inputStream = new FileInputStream(path);
            yamlData = (Map) yaml.load(inputStream);
        } catch (FileNotFoundException err) {
            System.err.println("> config file " + path + " was not found");
            System.exit(-1);
        }
        return new Mandelbrot(yamlData);
    }

    public Mandelbrot(Map<String, Object> config) {
        this.configure(config);
    }

    public boolean equalsConfig(Mandelbrot mandelbrot) {
        if (this.width != mandelbrot.width)
            return false;
        if (this.height != mandelbrot.height)
            return false;
        if (this.zMinRe != mandelbrot.zMaxRe)
            return false;
        if (this.zMinIm != mandelbrot.zMinIm)
            return false;
        if (this.zMaxRe != mandelbrot.zMaxRe)
            return false;
        if (this.zMaxIm != mandelbrot.zMaxIm)
            return false;
        if (this.nMax != mandelbrot.nMax)
            return false;
        if (this.color != mandelbrot.color)
            return false;
        if (this.gradient != mandelbrot.gradient)
            return false;
        return true;
    }

    public void saveImage(String outputPath) {

    }

    void configure(Map<String, Object> data) {
        this.width = (Integer) data.get("width");
        this.height = (Integer) data.get("height");
        this.zMinRe = (Double) data.get("minRe");
        this.zMinIm = (Double) data.get("minIm");
        this.zMaxRe = (Double) data.get("maxRe");
        this.zMaxIm = (Double) data.get("maxIm");
        this.nMax = (Integer) data.get("nMax");
        this.gradient = ((ArrayList<Integer>) data.get("gradient")).stream().mapToInt(i -> i).toArray();
        this.color = (Integer) data.get("color");
        this.palette = this.createColorPalette(this.color, this.gradient, this.nMax);
    }

    public int[] createColorPalette(int color, int[] gradient, int nMax) {
        int[] palette = new int[1];
        if (gradient.length > nMax - 1) {
            new Exception(OUT_OF_MEMORY_ERR).printStackTrace();
            System.exit(-1);
        }

        int tNum = gradient.length - 1;
        int tLength = (nMax - 1) / (gradient.length - 1);
        int r = (nMax - 1) % tNum;

        for (int i = 0; i < tNum - 1; ++i) {
            palette = this.combine(palette, this.transition(gradient[i], gradient[i + 1], tLength, false));
        }

        palette = this.combine(palette, this.transition(gradient[tNum - 1], gradient[tNum], tLength + r, true));
        return this.combine(palette, new int[] { color });
    }

    private int[] combine(int[] arr1, int[] arr2) {
        int[] result = new int[arr1.length + arr2.length];
        System.arraycopy(arr1, 0, result, 0, arr1.length);
        System.arraycopy(arr2, 0, result, arr1.length, arr2.length);
        return result;
    }

    private int hex(int red, int green, int blue) {
        return red * 65536 + green * 256 + blue;
    }

    private int[] transition(int start, int end, int s, boolean includeEnd) {
        int steps = includeEnd ? s - 1 : s;
        int[] res = new int[s];
        int rs = (start & 16711680) >> 16;
        int gs = (start & '\uff00') >> 8;
        int bs = start & 255;
        int re = (end & 16711680) >> 16;
        int ge = (end & '\uff00') >> 8;
        int be = end & 255;
        int rd = re - rs;
        int gd = ge - gs;
        int bd = be - bs;
        double rstep = (double) rd / (double) steps;
        double gstep = (double) gd / (double) steps;
        double bstep = (double) bd / (double) steps;

        for (int i = 0; i < s; ++i) {
            res[i] = this.hex((int) ((double) rs + rstep * (double) i), (int) ((double) gs + gstep * (double) i),
                    (int) ((double) bs + bstep * (double) i));
        }

        return res;
    }

    int[] toImage() {
        int[] img = new int[this.width * this.height];

        for (int i = 0; i < this.data.length; ++i) {
            img[i] = this.palette[this.data[i]];
        }

        return img;
    }

    int countActivePixels() {
        int c = 0;

        for (int i = 0; i < this.data.length; ++i) {
            if (this.data[i] == 1) {
                ++c;
            }
        }

        return c;
    }

    void export(String path) {
        try {
            int[] img = this.toImage();
            BufferedImage image = new BufferedImage(this.width, this.height, 1);
            image.setRGB(0, 0, this.width, this.height, img, 0, this.width);

            try {
                ImageIO.write(image, "jpg", new File(path));
            } catch (IOException var5) {
                var5.printStackTrace();
            }
        } catch (OutOfMemoryError err) {
            System.out.println(OUT_OF_MEMORY_ERR);
            System.exit(-1);
        }

    }

    void build(final DoubleRunnable onProgress, final Runnable onFinish) {

        // make sure we are on the Swing UI Thread
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> build(onProgress, onFinish));
            return;
        }

        double arImage = (double) this.width / (double) this.height;
        double arComplex = Math.abs(this.zMaxRe - this.zMinRe) / Math.abs(this.zMaxIm - this.zMinIm);
        if (arImage != arComplex) {
            (new Exception("Aspect ratios are not the same")).printStackTrace();
            System.exit(-1);
        }

        this.startTime = System.currentTimeMillis();
        this.data = new int[this.width * this.height];
        this.workers = new SwingWorker[this.NUMTHREADS];
        this.percentageCompleted = 0;
        this.rowsCompleted = 0;

        for (int i = 0; i < this.NUMTHREADS; i++) {
            final int k = i;
            if (this.workers[i] != null) {
                try {
                    this.workers[i].cancel(true);
                } catch (Exception var9) {
                }
            }

            this.workers[i] = new SwingWorker<int[], Integer>() {
                int xBegin;
                int xEnd;
                int xRange;
                int yBegin;
                int yEnd;
                int yRange;

                protected int[] doInBackground() {
                    this.xBegin = 0;
                    this.xEnd = width;
                    this.xRange = this.xEnd - this.xBegin;
                    this.yBegin = k * (height / NUMTHREADS);
                    this.yEnd = k * (height / NUMTHREADS) + height / NUMTHREADS;
                    this.yRange = this.yEnd - this.yBegin;
                    int[] part = new int[this.xRange * this.yRange];

                    for (int row = 0; row < this.yRange; ++row) {
                        for (int col = 0; col < this.xRange; ++col) {
                            double px = (double) (this.xBegin + col);
                            double py = (double) (this.yBegin + row);
                            double zOriginRe = zMinRe;
                            double zOriginIm = zMaxIm;
                            double s = Math.abs(zMaxRe - zMinRe) / (double) width;
                            double cRe = zOriginRe + s * px;
                            double cIm = zOriginIm - s * py;
                            part[row * this.xRange + col] = iterate(cRe, cIm);
                        }

                        this.publish(new Integer[] { row });
                    }

                    return part;
                }

                protected void process(List<Integer> rows) {
                    rowsCompleted = rowsCompleted + rows.size();
                    percentageCompleted = rowsCompleted * 100.0D / (double) height;
                    onProgress.run(percentageCompleted);
                }

                public void done() {
                    try {
                        int[] part = (int[]) this.get();
                        System.arraycopy(part, 0, data, part.length * k, part.length);
                    } catch (ExecutionException | InterruptedException var3) {
                        String message = var3.getCause().getMessage();
                        if (message.equals("Java heap space")) {
                            System.out.println(OUT_OF_MEMORY_ERR);
                            System.exit(-1);
                        }
                    }

                    ++finishedThreads;
                    if (finishedThreads == workers.length) {
                        finishedThreads = 0;
                        onFinish.run();
                    }

                }

            };
            this.workers[i].execute();
        }

    }

    private long countTotalIterations() {
        long count = 0L;

        for (int i = 0; i < this.data.length; ++i) {
            count += (long) this.data[i];
        }

        return count;
    }

    int iterate(double cRe, double cIm) {
        double zRe = 0.0D;
        double zIm = 0.0D;

        for (int n = 1; n <= this.nMax; ++n) {
            double sqrZRe = zRe * zRe - zIm * zIm;
            double sqrZIm = zRe * zIm + zIm * zRe;
            zRe = sqrZRe + cRe;
            zIm = sqrZIm + cIm;
            if (zRe * zRe + zIm * zIm > ESCAPE_RADIUS * ESCAPE_RADIUS) {
                return n;
            }
        }

        return this.nMax;
    }

    public void zoom(double re, double im, double factor) {
        double rangeRe = Math.abs(this.zMaxRe - this.zMinRe);
        double rangeIm = Math.abs(this.zMaxIm - this.zMinIm);
        this.zMinRe = -rangeRe / (2.0D * factor) + re;
        this.zMinIm = -rangeIm / (2.0D * factor) + im;
        this.zMaxRe = rangeRe / (2.0D * factor) + re;
        this.zMaxIm = rangeIm / (2.0D * factor) + im;
    }

    @FunctionalInterface
    interface DoubleRunnable {
        void run(double val);
    }
}

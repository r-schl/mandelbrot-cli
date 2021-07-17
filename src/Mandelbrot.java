import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.yaml.snakeyaml.Yaml;

public class Mandelbrot {
    private static final String OUT_OF_MEMORY_ERR = "\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<";
    private static final String ASPECT_RATIO_ERR = ">> Aspect ratios of the image and the section of the complex plane must be the same. <<";
    private static final int PROGRESS_BAR_WIDTH = 30;

    private final int NUMTHREADS = Runtime.getRuntime().availableProcessors();
    private final int ESCAPE_RADIUS = 2;

    /**
     * Configuration
     */
    private int width; // X-dimension of the image in pixels
    private int height; // Y-dimension of the image in pixels
    private double zMinRe; // Left side of the area of the complex plane
    private double zMinIm; // Bottom side of the area of the complex plane
    private double zMaxRe; // Right side of the area of the complex plane
    private double zMaxIm; // Top side of the area of the complex plane
    private int nMax; // Maximum number of iterations
    private int colorInside; // Color for points inside the mandelbrot set
    private int[] gradient; // Color gradient for points outside the set (first color diverges after 1
    // iteration; last color diverges after nMax-1 iterations)

    /**
     * Data for the calculation
     */
    private int[] data;
    private int rowsCompleted = 0;
    private double percentageCompleted = 0;
    private int finishedThreads = 0;
    private long startTime;
    private SwingWorker<int[], Integer>[] workers;
    private int[] palette;

    private boolean isBuilt = false;

    private static boolean isVerbose = false;
    private static boolean shouldOpen = false;

    public static void main(String[] args) {

        int k = 0;
        if (args[0].startsWith("-")) {
            k++;
            if (args[0].indexOf('o') != -1)
                shouldOpen = true;
            if (args[0].indexOf('v') != -1)
                isVerbose = true;
        }

        Mandelbrot mand = Mandelbrot.fromYamlFile(args[k]);
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
                System.out.println("   - set color: " + mand.colorInside);
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

    public static Mandelbrot fromYamlFile(String path) {
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

    /**
     * 
     * @param width    X-dimension of the image in pixels
     * @param height   Y-dimension of the image in pixels
     * @param zMinRe   Left side of the area of the complex plane
     * @param zMinIm   Bottom side of the area of the complex plane
     * @param zMaxRe   Right side of the area of the complex plane
     * @param zMaxIm   Top side of the area of the complex plane
     * @param nMax     Maximum number of iterations
     * @param color    Color for points inside the mandelbrot set
     * @param gradient Color gradient for points outside the set (first color
     *                 diverges after 1 iteration; last color diverges after nMax-1
     *                 iterations)
     */
    public Mandelbrot(int width, int height, double zMinRe, double zMinIm, double zMaxRe, double zMaxIm, int nMax,
            int colorInside, int[] gradient) {
        this.width = width;
        this.height = height;
        this.zMinRe = zMinRe;
        this.zMinIm = zMinIm;
        this.zMaxRe = zMaxRe;
        this.zMaxIm = zMaxIm;
        this.nMax = nMax;
        this.colorInside = colorInside;
        this.gradient = gradient;
        this.palette = createColorPalette(this.colorInside, this.gradient, this.nMax);
    }

    public Mandelbrot(Mandelbrot other) {
        this(other.width, other.height, other.zMinRe, other.zMinIm, other.zMaxRe, other.zMaxIm, other.nMax,
                other.colorInside, other.gradient);
    }

    public Mandelbrot(Map<String, Object> config) {
        this.width = (Integer) config.get("width");
        this.height = (Integer) config.get("height");
        this.zMinRe = (Double) config.get("minRe");
        this.zMinIm = (Double) config.get("minIm");
        this.zMaxRe = (Double) config.get("maxRe");
        this.zMaxIm = (Double) config.get("maxIm");
        this.nMax = (Integer) config.get("nMax");
        this.gradient = ((ArrayList<Integer>) config.get("gradient")).stream().mapToInt(i -> i).toArray();
        this.colorInside = (Integer) config.get("color");
        this.palette = createColorPalette(this.colorInside, this.gradient, this.nMax);
    }

    // Get Methods

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public double getMinRe() {
        return this.zMinRe;
    }

    public double getMinIm() {
        return this.zMinIm;
    }

    public double getMaxRe() {
        return this.zMaxRe;
    }

    public double getMaxIm() {
        return this.zMaxIm;
    }

    public int getNMax() {
        return this.nMax;
    }

    public int getColorInside() {
        return this.colorInside;
    }

    public int[] getGradient() {
        return this.gradient;
    }

    public int[] getData() {
        if (!isBuilt)
            return null;
        return this.data;
    }

    public int[] getImage() {
        if (!isBuilt)
            return null;
        int[] img = new int[this.width * this.height];
        for (int i = 0; i < this.data.length; ++i) {
            img[i] = this.palette[this.data[i]];
        }
        return img;
    }

    public boolean isBuilt() {
        return this.isBuilt;
    }

    /**
     * This method returns a new Mandelbrot object with the same configurations as
     * this one.
     * 
     * @return a copy of this Mandelbrot object
     */
    public Mandelbrot getCopy() {
        return new Mandelbrot(this);
    }

    /**
     * This method returns a new Mandelbrot object that is created by enlarging the
     * area of the complex plane of this object by a certain factor.
     * 
     * @param re     real part of a number of the complex plane
     * @param im     imaginary part of a number of the complex plane
     * @param factor zoom factor
     * @return
     */
    public Mandelbrot getZoom(double re, double im, double factor) {
        double rangeRe = Math.abs(this.zMaxRe - this.zMinRe);
        double rangeIm = Math.abs(this.zMaxIm - this.zMinIm);
        double newZMinRe = -rangeRe / (2.0D * factor) + re;
        double newZMinIm = -rangeIm / (2.0D * factor) + im;
        double newZMaxRe = rangeRe / (2.0D * factor) + re;
        double newZMaxIm = rangeIm / (2.0D * factor) + im;
        return new Mandelbrot(this.width, this.height, newZMinRe, newZMinIm, newZMaxRe, newZMaxIm, this.nMax,
                this.colorInside, this.gradient);
    }

    /**
     * This Methods checks if the configuration of two Mandelbrot objects are the
     * same.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Mandelbrot))
            return false;
        Mandelbrot mandelbrot = (Mandelbrot) o;
        if (this.width != mandelbrot.width)
            return false;
        if (this.height != mandelbrot.height)
            return false;
        if (this.zMinRe != mandelbrot.zMinRe)
            return false;
        if (this.zMinIm != mandelbrot.zMinIm)
            return false;
        if (this.zMaxRe != mandelbrot.zMaxRe)
            return false;
        if (this.zMaxIm != mandelbrot.zMaxIm)
            return false;
        if (this.nMax != mandelbrot.nMax)
            return false;
        if (this.colorInside != mandelbrot.colorInside)
            return false;
        if (!Arrays.equals(this.gradient, mandelbrot.gradient))
            return false;
        return true;
    }

    /**
     * This Methods creates a color palette based on a color for the inside of the
     * set and a color gradient characterized by multiple colors
     * 
     * @param color    color for the inside of the set
     * @param gradient color gradient for the outside of the set
     * @param nMax     maximum number of iterations
     * @return
     */
    private int[] createColorPalette(int color, int[] gradient, int nMax) {
        if (gradient.length == 0) {
            System.err.println(">> User must specify at least one color of the color gradient. <<");
            System.exit(-1);
        }

        if (gradient.length == 1) {
            int[] palette = new int[nMax + 1];
            for (int i = 1; i < nMax; i++) {
                palette[i] = gradient[0];
            }
            palette[nMax] = color;
            return palette;
        }

        float[] fractions = new float[gradient.length];
        for (int i = 0; i < fractions.length; i++)
            fractions[i] = ((float) 1 / gradient.length) * i;
        Color[] colorsGradient = new Color[gradient.length];
        for (int i = 0; i < colorsGradient.length; i++)
            colorsGradient[i] = new Color(gradient[i]);
        LinearGradientPaint p = new LinearGradientPaint(0, 0, nMax - 1, 1, fractions, colorsGradient);
        BufferedImage bi = new BufferedImage(nMax - 1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bi.createGraphics();
        g2d.setPaint(p);
        g2d.fillRect(0, 0, nMax - 1, 1);
        g2d.dispose();
        int[] palette = new int[nMax + 1]; // because index 0 is placeholder
        for (int i = 0; i < bi.getWidth(); i++)
            palette[i + 1] = bi.getRGB(i, 0);
        palette[nMax] = color;
        return palette;
    }

    /**
     * This method exports the Mandelbrot object into an image. It can only be
     * called when finished building.
     * 
     * @param path export path
     */
    public void export(String path) {
        try {
            int[] img = this.getImage();
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

    public void build(final Runnable onFinish) {
        this.build((percentage) -> {
            // empty
        }, onFinish);
    }

    public void build(final DoubleRunnable onProgress, final Runnable onFinish) {

        // make sure we are on the Swing UI Thread
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> build(onProgress, onFinish));
            return;
        }

        if (isBuilt) {
            // the mandelbrot set is already built, no need to recalculate
            onProgress.run(100);
            onFinish.run();
            return;
        }

        double arImage = (double) this.width / (double) this.height;
        double arComplex = Math.abs(this.zMaxRe - this.zMinRe) / Math.abs(this.zMaxIm - this.zMinIm);
        if (arImage != arComplex) {
            System.err.print(ASPECT_RATIO_ERR);
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
                        isBuilt = true;
                        onFinish.run();
                        // clear the data
                        data = null;
                        workers = null;
                        palette = null;
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

    private int iterate(double cRe, double cIm) {
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

    @FunctionalInterface
    interface DoubleRunnable {
        void run(double val);
    }
}

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
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
import org.yaml.snakeyaml.error.YAMLException;

public class Mandelbrot {
    private static final String OUT_OF_MEMORY_ERR = "\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<";
    private static final int PROGRESS_BAR_WIDTH = 30;
    private static final double DIFF_EQUAL = 1E-7;

    private final int NUMTHREADS = Runtime.getRuntime().availableProcessors();
    private final int ESCAPE_RADIUS = 2;
    private final int BACKGROUND = 0xFFFFFF;

    /**
     * Configuration
     */
    private double minRe; // Left side of the area of the complex plane
    private double minIm; // Bottom side of the area of the complex plane
    private double maxRe; // Right side of the area of the complex plane
    private double maxIm; // Top side of the area of the complex plane
    private int nMax; // Maximum number of iterations
    private int innerColor; // Color for points inside the mandelbrot set
    private int[] colorGradient; // A color gradient for all points outside of the mandelbrot set

    /**
     * Data for the calculation
     */
    private int[] data;
    private int rowsCompleted = 0;
    private double percentageCompleted = 0;
    private int finishedThreads = 0;
    private long startTime;
    private SwingWorker<int[], Integer>[] workerThreads;
    private int[] colorPallete;

    private int imageWidth; // Width of the image
    private int imageHeight; // Height of the image

    private int areaWidth; // Width of the area of the image where the mandelbrot set is displayed
    private int areaHeight; // Height of the area of the image where the mandelbrot set is displayed

    private boolean isBuilt = false;
    private boolean isBuilding = false;
    private boolean hasBeenAborted = false;

    private static boolean isVerbose = false;
    private static boolean shouldOpen = false;

    public Mandelbrot(Mandelbrot other) {
        this(other.imageWidth, other.imageHeight, other.minRe, other.minIm, other.maxRe, other.maxIm, other.nMax,
                other.innerColor, other.colorGradient);
    }

    public Mandelbrot(Map<String, Object> config, int imageWidth, int imageHeight)
            throws CorruptYAMLDataException, IllegalArgumentException {
        try {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.minRe = (Double) config.get("minRe");
            this.minIm = (Double) config.get("minIm");
            this.maxRe = (Double) config.get("maxRe");
            this.maxIm = (Double) config.get("maxIm");
            double lenRe = maxRe - minRe;
            double lenIm = maxIm - minIm;
            if (lenRe < 0 || Math.abs(lenRe) < DIFF_EQUAL || lenIm < 0 || Math.abs(lenIm) < DIFF_EQUAL)
                throw new IllegalArgumentException("The area of the complex plane is invalid or too small");
            this.calculateAreaDimensions(this.imageWidth, this.imageHeight);
            this.nMax = (Integer) config.get("nMax");
            this.innerColor = (Integer) config.get("innerColor");
            this.colorGradient = ((ArrayList<Integer>) config.get("colorGradient")).stream().mapToInt(i -> i).toArray();
            if (this.colorGradient.length == 0)
                throw new IllegalArgumentException("There must be at least one color in the color gradient");
            this.colorPallete = createColorPalette(this.innerColor, this.colorGradient, this.nMax);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw e;
            } else
                throw new CorruptYAMLDataException("YAML Data was corrupt or not complete");
        }

    }

    /**
     * 
     * @param width    X-dimension of the image in pixels
     * @param height   Y-dimension of the image in pixels
     * @param minRe    Left side of the area of the complex plane
     * @param minIm    Bottom side of the area of the complex plane
     * @param maxRe    Right side of the area of the complex plane
     * @param maxIm    Top side of the area of the complex plane
     * @param nMax     Maximum number of iterations
     * @param color    Color for points inside the mandelbrot set
     * @param gradient Color gradient for points outside the set (first color
     *                 diverges after 1 iteration; last color diverges after nMax-1
     *                 iterations)
     */
    public Mandelbrot(int imageWidth, int imageHeight, double minRe, double minIm, double maxRe, double maxIm, int nMax,
            int innerColor, int[] colorGradient) throws IllegalArgumentException {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.minRe = minRe;
        this.minIm = minIm;
        this.maxRe = maxRe;
        this.maxIm = maxIm;
        double lenRe = maxRe - minRe;
        double lenIm = maxIm - minIm;
        if (lenRe < 0 || Math.abs(lenRe) < DIFF_EQUAL || lenIm < 0 || Math.abs(lenIm) < DIFF_EQUAL)
            throw new IllegalArgumentException("The area of the complex plane is too small");
        this.calculateAreaDimensions(this.imageWidth, this.imageHeight);
        this.nMax = nMax;
        this.innerColor = innerColor;
        this.colorGradient = colorGradient;
        if (this.colorGradient.length == 0)
            throw new IllegalArgumentException("There must be at least one color in the color gradient");
        this.colorPallete = createColorPalette(this.innerColor, this.colorGradient, this.nMax);
    }

    private void calculateAreaDimensions(int imageWidth, int imageHeight) {
        double rangeRe = Math.abs(maxRe - minRe);
        double rangeIm = Math.abs(maxIm - minIm);

        this.areaWidth = (int) Math.ceil((double) imageHeight * (rangeRe / rangeIm));
        this.areaHeight = (int) imageHeight;

        if (this.areaWidth > imageWidth) {
            this.areaWidth = imageWidth;
            this.areaHeight = (int) Math.ceil((double) imageWidth * (rangeIm / rangeRe));
        }
    }

    // GETTERS

    public boolean isBuilt() {
        return this.isBuilt;
    }

    public boolean hasBeenAborted() {
        return this.hasBeenAborted;
    }

    public boolean isBuilding() {
        return this.isBuilding;
    }

    public int getImageWidth() {
        return this.imageWidth;
    }

    public int getImageHeight() {
        return this.imageHeight;
    }

    public int getAreaWidth() {
        return this.areaWidth;
    }

    public int getAreaHeight() {
        return this.areaHeight;
    }

    public double getMinRe() {
        return this.minRe;
    }

    public double getMinIm() {
        return this.minIm;
    }

    public double getMaxRe() {
        return this.maxRe;
    }

    public double getMaxIm() {
        return this.maxIm;
    }

    public int getNMax() {
        return this.nMax;
    }

    public int getInnerColor() {
        return this.innerColor;
    }

    public int[] getColorGradient() {
        return this.colorGradient;
    }

    public int[] getData() {
        if (!isBuilt)
            return null;
        return this.data;
    }

    public int[] getImageData() {
        if (!isBuilt)
            return null;

        int[] img = new int[this.imageWidth * this.imageHeight];
        Arrays.fill(img, BACKGROUND);
        int startX = (int) Math.ceil((this.imageWidth - this.areaWidth) / 2.0D);
        int endX = this.imageWidth - (int) Math.floor((this.imageWidth - this.areaWidth) / 2.0D);
        int startY = (int) Math.ceil((this.imageHeight - this.areaHeight) / 2.0D);
        int endY = this.imageHeight - (int) Math.floor((this.imageHeight - this.areaHeight) / 2.0D);
        int i = 0;
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                img[this.imageWidth * y + x] = this.colorPallete[this.data[i]];
                i++;
            }
        }
        return img;
    }

    public Mandelbrot getExtendedToImageSize() {

        double widthFactor = (double) imageWidth / (double) areaWidth;
        double heightFactor = (double) imageHeight / (double) areaHeight;

        double lengthRe = (maxRe - minRe);
        double lengthIm = (maxIm - minIm);
        if (Math.abs(widthFactor - 1.0D) < DIFF_EQUAL)
            lengthIm = lengthRe * ((double) imageHeight / imageWidth);

        if (Math.abs(heightFactor - 1.0D) < DIFF_EQUAL)
            lengthRe = lengthIm * ((double) imageWidth / imageHeight);

        double diffRe = (lengthRe - (maxRe - minRe)) / 2.0D;
        double diffIm = (lengthIm - (maxIm - minIm)) / 2.0D;

        double minReNew = minRe - diffRe;
        double minImNew = minIm - diffIm;
        double maxReNew = minReNew + lengthRe;
        double maxImNew = minImNew + lengthIm;

        return new Mandelbrot(imageWidth, imageHeight, minReNew, minImNew, maxReNew, maxImNew, nMax, innerColor,
                colorGradient);
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
        double rangeRe = Math.abs(this.maxRe - this.minRe);
        double rangeIm = Math.abs(this.maxIm - this.minIm);
        double newminRe = -rangeRe / (2.0D * factor) + re;
        double newminIm = -rangeIm / (2.0D * factor) + im;
        double newmaxRe = rangeRe / (2.0D * factor) + re;
        double newmaxIm = rangeIm / (2.0D * factor) + im;
        return new Mandelbrot(this.imageWidth, this.imageHeight, newminRe, newminIm, newmaxRe, newmaxIm, this.nMax,
                this.innerColor, this.colorGradient);
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
        if (this.imageWidth != mandelbrot.imageWidth)
            return false;
        if (this.imageHeight != mandelbrot.imageHeight)
            return false;
        if (this.minRe != mandelbrot.minRe)
            return false;
        if (this.minIm != mandelbrot.minIm)
            return false;
        if (this.maxRe != mandelbrot.maxRe)
            return false;
        if (this.maxIm != mandelbrot.maxIm)
            return false;
        if (this.nMax != mandelbrot.nMax)
            return false;
        if (this.innerColor != mandelbrot.innerColor)
            return false;
        if (!Arrays.equals(this.colorGradient, mandelbrot.colorGradient))
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
    private int[] createColorPalette(int color, int[] gradient, int nMax) throws IllegalArgumentException {
        if (gradient.length == 0)
            new IllegalArgumentException("There must be at least one color in the color gradient");

        if (nMax == 0)
            return new int[] { color };

        if (gradient.length == 1) {
            int[] palette = new int[nMax + 1];
            for (int i = 0; i < nMax; i++)
                palette[i] = gradient[0];
            palette[nMax] = color;
            return palette;
        }

        // reverse the gradient
        int[] gradientRev = new int[gradient.length];
        int c = 0;
        for (int i = gradient.length - 1; i >= 0; i--)
            gradientRev[c++] = gradient[i];

        // build the color palette
        float[] fractions = new float[gradientRev.length];
        for (int i = 0; i < fractions.length; i++)
            fractions[i] = ((float) 1 / gradientRev.length) * i;
        Color[] colorsGradient = new Color[gradientRev.length];
        for (int i = 0; i < colorsGradient.length; i++)
            colorsGradient[i] = new Color(gradientRev[i]);
        LinearGradientPaint p = new LinearGradientPaint(0, 0, nMax, 1, fractions, colorsGradient);
        BufferedImage bi = new BufferedImage(nMax, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bi.createGraphics();
        g2d.setPaint(p);
        g2d.fillRect(0, 0, nMax, 1);
        g2d.dispose();

        int[] palette = new int[nMax + 1];
        for (int i = 0; i < bi.getWidth(); i++)
            palette[i] = bi.getRGB(i, 0);
        palette[nMax] = color;
        return palette;
    }

    /**
     * This method exports the Mandelbrot object into an image. It can only be
     * called when finished building.
     * 
     * @param path export path
     */
    public void saveAsImage(String path) {
        try {
            int[] img = this.getImageData();
            BufferedImage image = new BufferedImage(this.imageWidth, this.imageHeight, 1);
            image.setRGB(0, 0, this.imageWidth, this.imageHeight, img, 0, this.imageWidth);
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

        if (isBuilding) {
            return;
        }

        if (isBuilt) {
            onProgress.run(100);
            onFinish.run();
            return;
        }

        this.startTime = System.currentTimeMillis();
        this.data = new int[this.areaWidth * this.areaHeight];
        this.workerThreads = new SwingWorker[this.NUMTHREADS];
        this.percentageCompleted = 0;
        this.rowsCompleted = 0;

        this.isBuilding = true;

        for (int i = 0; i < this.NUMTHREADS; i++) {
            final int k = i;

            this.workerThreads[i] = new SwingWorker<int[], Integer>() {
                int xBegin;
                int xEnd;
                int xRange;
                int yBegin;
                int yEnd;
                int yRange;

                protected int[] doInBackground() {
                    this.xBegin = 0;
                    this.xEnd = areaWidth;
                    this.xRange = this.xEnd - this.xBegin;
                    this.yBegin = k * (areaHeight / NUMTHREADS);
                    this.yEnd = k * (areaHeight / NUMTHREADS) + areaHeight / NUMTHREADS;
                    this.yRange = this.yEnd - this.yBegin;
                    int[] part = new int[this.xRange * this.yRange];

                    for (int row = 0; row < this.yRange; ++row) {
                        for (int col = 0; col < this.xRange; ++col) {
                            double px = (double) (this.xBegin + col);
                            double py = (double) (this.yBegin + row);
                            double zOriginRe = minRe;
                            double zOriginIm = maxIm;
                            double s = Math.abs(maxRe - minRe) / (double) areaWidth;
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
                    percentageCompleted = rowsCompleted * 100.0D / (double) areaHeight;
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
                    if (finishedThreads == workerThreads.length) {
                        finishedThreads = 0;
                        isBuilt = true;
                        isBuilding = false;
                        onFinish.run();
                    }

                }

            };
            this.workerThreads[i].execute();
        }
    }

    public void abort() {
        this.isBuilding = false;
        if (this.workerThreads == null)
            return;
        for (int i = 0; i < this.NUMTHREADS; i++) {
            if (this.workerThreads[i] != null && !this.workerThreads[i].isDone()) {
                this.hasBeenAborted = true;
                try {
                    this.workerThreads[i].cancel(true);
                } catch (Exception var9) {
                }
            }
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
        for (int n = 0; n < this.nMax; ++n) {
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

    public void exportToYAML(String path) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(path, "UTF-8");
        writer.println("# Area of the complex plane given by two numbers 'min' and 'max'");
        writer.println("minRe: " + this.minRe);
        writer.println("minIm: " + this.minIm);
        writer.println("maxRe: " + this.maxRe);
        writer.println("maxIm: " + this.maxIm);
        writer.println("# Max amount of iterations (iteration depth)");
        writer.println("nMax: " + this.nMax);
        writer.println("# Color (hexadecimal representation) for points inside of the mandelbrot set");
        writer.println("innerColor: " + this.innerColor);
        writer.println("# Color gradient (hexadecimal representation) for points outside of the mandelbrot set");
        writer.println("# > First color for n_max-1 iterations reached");
        writer.println("# > Last color for 0 iterations reached");
        writer.println("# At least one color must be specified");
        writer.println("colorGradient: " + Arrays.toString(this.colorGradient));
        writer.close();
    }

    public static void main(String[] args) {

        int k = 0;
        if (args[0].startsWith("-")) {
            k++;
            if (args[0].indexOf('o') != -1)
                shouldOpen = true;
            if (args[0].indexOf('v') != -1)
                isVerbose = true;
        }

        String configFile = args[k];
        int imageWidth = Integer.parseInt(args[k + 1]);
        int imageHeight = Integer.parseInt(args[k + 2]);
        String outputPath = args[k + 3];
        try {

            Mandelbrot mand = Mandelbrot.fromYAMLFile(configFile, imageWidth, imageHeight);

            try {
                mand.exportToYAML(configFile);
            } catch (UnsupportedEncodingException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            mand.build((percentage) -> { // On progress update

                String progressStr = "";
                while (progressStr.length() < PROGRESS_BAR_WIDTH * (percentage / 100.0D))
                    progressStr += "#";
                while (progressStr.length() < PROGRESS_BAR_WIDTH)
                    progressStr += " ";
                String str = "|" + progressStr + "| " + (double) Math.round(percentage * 10.0D) / 10.0D + "%";
                str += percentage != 100.0D ? "   [in progress] \r" : "   [done]  ";
                System.out.print(str);

            }, () -> { // On finish

                // Print a full progress bar
                String str = "";
                for (int i = 0; i < PROGRESS_BAR_WIDTH; i++)
                    str += "#";
                System.out.print("|" + str + "| " + "100%   [done]  ");

                if (isVerbose) {
                    System.out.println("       ");
                    System.out.println();
                    System.out.println("> output file: " + outputPath);
                    System.out.println("> image size: " + mand.imageWidth + "x" + mand.imageHeight);
                    System.out.println("> size of the mandelbrot area: " + mand.areaWidth + "x" + mand.areaHeight);
                    System.out.println("> configurations (" + configFile + "): ");
                    System.out.println("   - min complex number: " + mand.minRe + (mand.minIm > 0.0D ? "+" : "")
                            + mand.minIm + "i");
                    System.out.println("   - max complex number: " + mand.maxRe + (mand.maxIm > 0.0D ? "+" : "")
                            + mand.maxIm + "i");
                    System.out.println("   - max iterations: " + mand.nMax);
                    System.out.println("   - inner color: " + mand.innerColor);
                    System.out.println("   - color gradient: " + Arrays.toString(mand.colorGradient));
                    System.out.println("> build information: ");
                    System.out.println("   - build time: "
                            + (double) (System.currentTimeMillis() - mand.startTime) / 1000.0D + "s");
                    long numIterationsTotal = mand.countTotalIterations();
                    System.out.println("   - total number of iterations: " + numIterationsTotal);
                    System.out.println("   - average number of iterations per pixel: "
                            + (double) Math.round((double) numIterationsTotal / (double) mand.data.length * 100.0D)
                                    / 100.0D);
                } else {
                    System.out.println("> output: " + outputPath);
                }

                mand.saveAsImage(outputPath);

                if (shouldOpen) {
                    try {
                        Desktop.getDesktop().open(new File(outputPath));
                    } catch (IOException err) {
                    }
                }
                ///////////////////////
            });
        } catch (FileNotFoundException | YAMLException e) {
            System.out
                    .println("The configuration file '" + configFile + "' was not found or the YAML data was corrupt");
            System.exit(-1);
        }

    }

    public static Mandelbrot fromYAMLFile(String path, int imageWidth, int imageHeight)
            throws FileNotFoundException, YAMLException, CorruptYAMLDataException {
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData = null;
        InputStream inputStream = new FileInputStream(path);
        yamlData = (Map) yaml.load(inputStream);
        return new Mandelbrot(yamlData, imageWidth, imageHeight);
    }

    class CorruptYAMLDataException extends RuntimeException {
        public CorruptYAMLDataException(String message) {
            super(message);
        }

        public CorruptYAMLDataException(Throwable cause) {
            super(cause);
        }

        public CorruptYAMLDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    interface DoubleRunnable {
        void run(double val);
    }
}

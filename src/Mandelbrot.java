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
    private static final double DIFF_EQUAL = 1E-15;

    private final int NUMTHREADS = Runtime.getRuntime().availableProcessors();
    private final int ESCAPE_RADIUS = 2;

    public boolean useBackgroundPattern = true;
    public int backgroundColor = 0xFFFFFF;

    public int patternColor1 = 0xcfcfcf;
    public int patternColor2 = 0x999999;

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
    private int[] iterationData;
    private int rowsCompleted = 0;
    private int percentageCompleted = 0;
    private int finishedThreads = 0;
    private long startTime;
    private SwingWorker<int[], Integer>[] workerThreads;
    private int[] colorPallete;

    private int fullWidth; // Width of the image
    private int fullHeight; // Height of the image

    private int areaWidth; // Width of the area of the image where the mandelbrot set is displayed
    private int areaHeight; // Height of the area of the image where the mandelbrot set is displayed

    private int[] areaRGBArray = null;
    private int[] imageRGBArray = null;
    private BufferedImage areaImage = null;
    private BufferedImage fullImage = null;

    private int offsetX;
    private int offsetY;

    private boolean isBuilt = false;
    private boolean isBuilding = false;
    private boolean hasBeenAborted = false;

    private static boolean isVerbose = false;
    private static boolean shouldOpen = false;

    public Mandelbrot(Mandelbrot other) {
        this(other.fullWidth, other.fullHeight, other.minRe, other.minIm, other.maxRe, other.maxIm, other.nMax,
                other.innerColor, other.colorGradient);
    }

    public Mandelbrot(Map<String, Object> config, int fullWidth, int fullHeight)
            throws MandelbrotConfigException, IllegalArgumentException {
        try {
            this.fullWidth = fullWidth;
            this.fullHeight = fullHeight;
            this.minRe = (Double) config.get("minRe");
            this.minIm = (Double) config.get("minIm");
            this.maxRe = (Double) config.get("maxRe");
            this.maxIm = (Double) config.get("maxIm");
            double lenRe = maxRe - minRe;
            double lenIm = maxIm - minIm;
            if (lenRe < 0 || Math.abs(lenRe) < DIFF_EQUAL || lenIm < 0 || Math.abs(lenIm) < DIFF_EQUAL)
                throw new IllegalArgumentException("The area of the complex plane is invalid or too small");
            this.calculateAreaDimensions(this.fullWidth, this.fullHeight);
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
                throw new MandelbrotConfigException("YAML Data was corrupt or not complete");
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
    public Mandelbrot(int fullWidth, int fullHeight, double minRe, double minIm, double maxRe, double maxIm, int nMax,
            int innerColor, int[] colorGradient) throws IllegalArgumentException {
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.minRe = minRe;
        this.minIm = minIm;
        this.maxRe = maxRe;
        this.maxIm = maxIm;
        double lenRe = maxRe - minRe;
        double lenIm = maxIm - minIm;
        if (lenRe < 0 || Math.abs(lenRe) < DIFF_EQUAL || lenIm < 0 || Math.abs(lenIm) < DIFF_EQUAL)
            throw new IllegalArgumentException("The area of the complex plane is too small");
        this.calculateAreaDimensions(this.fullWidth, this.fullHeight);
        this.nMax = nMax;
        this.innerColor = innerColor;
        this.colorGradient = colorGradient;
        if (this.colorGradient.length == 0)
            throw new IllegalArgumentException("There must be at least one color in the color gradient");
        this.colorPallete = createColorPalette(this.innerColor, this.colorGradient, this.nMax);
    }

    private void calculateAreaDimensions(int fullWidth, int fullHeight) {
        double rangeRe = Math.abs(maxRe - minRe);
        double rangeIm = Math.abs(maxIm - minIm);

        this.areaWidth = (int) Math.ceil((double) fullHeight * (rangeRe / rangeIm));
        this.areaHeight = (int) fullHeight;

        if (this.areaWidth > fullWidth) {
            this.areaWidth = fullWidth;
            this.areaHeight = (int) Math.ceil((double) fullWidth * (rangeIm / rangeRe));
        }

        this.offsetX = (int) Math.ceil((this.fullWidth - this.areaWidth) / 2.0D);
        this.offsetY = (int) Math.ceil((this.fullHeight - this.areaHeight) / 2.0D);
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

    public int getFullWidth() {
        return this.fullWidth;
    }

    public int getFullHeight() {
        return this.fullHeight;
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

    public double getRangeRe() {
        return this.maxRe - this.minRe;
    }

    public double getRangeIm() {
        return this.maxIm - this.minIm;
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

    public int getOffsetX() {
        return this.offsetX;
    }

    public int getOffsetY() {
        return this.offsetY;
    }

    public int[] getAreaIterationArray() {
        if (!isBuilt)
            return null;
        return this.iterationData;
    }

    public int[] getAreaRGBArray() {
        if (!isBuilt)
            return null;
        if (this.areaRGBArray != null)
            return this.areaRGBArray;
        this.areaRGBArray = new int[this.areaWidth * this.areaHeight];
        for (int i = 0; i < this.iterationData.length; i++)
            this.areaRGBArray[i] = this.colorPallete[this.iterationData[i]];
        return this.areaRGBArray;
    }

    public BufferedImage getAreaImage() {
        if (!this.isBuilt)
            return null;
        if (this.areaImage != null)
            return this.areaImage;
        this.areaImage = new BufferedImage(this.areaWidth, this.areaHeight, BufferedImage.TYPE_INT_RGB);
        this.areaImage.setRGB(0, 0, this.areaWidth, this.areaHeight, this.getAreaRGBArray(), 0, this.areaWidth);
        return this.areaImage;
    }

    public int[] getBackgroundPattern() {
        int[] pattern = new int[this.fullWidth * this.fullHeight];
        int s = 7;
        int maxC = (this.fullWidth - this.fullWidth % s) / s + ((this.fullWidth % s > 0) ? 1 : 0);
        int maxR = (this.fullHeight - this.fullHeight % s) / s + ((this.fullHeight % s > 0) ? 1 : 0);
        for (int c = 0; c < maxC; c += 1) {
            for (int r = 0; r < maxR; r += 1) {
                int rgb = (c % 2 == r % 2) ? this.patternColor1 : this.patternColor2;
                for (int x = 0; x < s; x++) {
                    for (int y = 0; y < s; y++) {
                        if (r * s + y < this.fullHeight && c * s + x < this.fullWidth)
                            pattern[(r * s + y) * this.fullWidth + (c * s + x)] = rgb;
                    }
                }
            }
        }
        return pattern;
    }

    public int[] getFullRGBArray() {
        if (!this.isBuilt)
            return null;
        if (this.imageRGBArray != null)
            return this.imageRGBArray;

        if (this.useBackgroundPattern)
            this.imageRGBArray = this.getBackgroundPattern();
        else {
            this.imageRGBArray = new int[this.fullWidth * this.fullHeight];
            Arrays.fill(this.imageRGBArray, this.backgroundColor);
        }

        int[] areaRGBArray = this.getAreaRGBArray();

        int i = 0;
        for (int y = offsetY; y < offsetY + this.areaHeight; y++) {
            for (int x = offsetX; x < offsetX + this.areaWidth; x++) {
                this.imageRGBArray[this.fullWidth * y + x] = areaRGBArray[i++];
            }
        }
        return this.imageRGBArray;
    }

    public BufferedImage getFullImage() {
        if (this.fullImage != null)
            return this.fullImage;
        this.fullImage = new BufferedImage(this.fullWidth, this.fullHeight, BufferedImage.TYPE_INT_RGB);
        this.fullImage.setRGB(0, 0, this.fullWidth, this.fullHeight, this.getFullRGBArray(), 0, this.fullWidth);
        return this.fullImage;
    }

    public Mandelbrot extendAreaToImageSize() {

        double widthFactor = (double) fullWidth / (double) areaWidth;
        double heightFactor = (double) fullHeight / (double) areaHeight;

        double lengthRe = (maxRe - minRe);
        double lengthIm = (maxIm - minIm);
        if (Math.abs(widthFactor - 1.0D) < DIFF_EQUAL)
            lengthIm = lengthRe * ((double) fullHeight / fullWidth);

        if (Math.abs(heightFactor - 1.0D) < DIFF_EQUAL)
            lengthRe = lengthIm * ((double) fullWidth / fullHeight);

        double diffRe = (lengthRe - (maxRe - minRe)) / 2.0D;
        double diffIm = (lengthIm - (maxIm - minIm)) / 2.0D;

        double minReNew = minRe - diffRe;
        double minImNew = minIm - diffIm;
        double maxReNew = minReNew + lengthRe;
        double maxImNew = minImNew + lengthIm;

        return new Mandelbrot(fullWidth, fullHeight, minReNew, minImNew, maxReNew, maxImNew, nMax, innerColor,
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
    public Mandelbrot zoom(double re, double im, double factor) {
        double rangeRe = Math.abs(this.maxRe - this.minRe);
        double rangeIm = Math.abs(this.maxIm - this.minIm);
        double newminRe = -rangeRe / (2.0D * factor) + re;
        double newminIm = -rangeIm / (2.0D * factor) + im;
        double newmaxRe = rangeRe / (2.0D * factor) + re;
        double newmaxIm = rangeIm / (2.0D * factor) + im;
        return new Mandelbrot(this.fullWidth, this.fullHeight, newminRe, newminIm, newmaxRe, newmaxIm, this.nMax,
                this.innerColor, this.colorGradient);
    }

    public Mandelbrot resizeImage(int fullWidth, int fullHeight) {
        return new Mandelbrot(fullWidth, fullHeight, this.minRe, this.minIm, this.maxRe, this.maxIm, this.nMax,
                this.innerColor, this.colorGradient);
    }

    public Mandelbrot lolToSize(int fullWidth, int fullHeight) {

        double widthFactor = (double) fullWidth / (double) this.areaWidth;
        double heightFactor = (double) fullHeight / (double) this.areaHeight;

        double maxReNew = this.minRe + Math.abs(this.maxRe - this.minRe) * widthFactor;
        double minImNew = this.maxIm - Math.abs(this.maxIm - this.minIm) * heightFactor;

        return new Mandelbrot(fullWidth, fullHeight, this.minRe, minImNew, maxReNew, this.maxIm, this.nMax,
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
        if (this.fullWidth != mandelbrot.fullWidth)
            return false;
        if (this.fullHeight != mandelbrot.fullHeight)
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
    public void exportImage(String path) throws OutOfMemoryError {
        try {
            try {
                ImageIO.write(this.getFullImage(), "jpg", new File(path));
            } catch (IOException var5) {
                var5.printStackTrace();
            }
        } catch (OutOfMemoryError err) {
            throw new OutOfMemoryError("Not enough allocated storage");
        }
    }

    public void saveAreaAsPicture(String path) throws OutOfMemoryError {
        try {
            try {
                ImageIO.write(this.getAreaImage(), "jpg", new File(path));
            } catch (IOException var5) {
                var5.printStackTrace();
            }
        } catch (OutOfMemoryError err) {
            throw new OutOfMemoryError("Not enough allocated storage");
        }
    }

    public void build(final Runnable onFinish) {
        this.build((percentage) -> {
            // empty
        }, onFinish);
    }

    public void build(final Executable<Integer> onProgress, final Runnable onFinish) throws OutOfMemoryError {

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
        try {
            this.iterationData = new int[this.areaWidth * this.areaHeight];
            this.workerThreads = new SwingWorker[this.NUMTHREADS];
        } catch (OutOfMemoryError err) {
            throw new OutOfMemoryError("Not enough allocated storage");
        }
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
                    try {
                        this.xBegin = 0;
                        this.xEnd = areaWidth;
                        this.xRange = this.xEnd - this.xBegin;
                        this.yBegin = k * (areaHeight / NUMTHREADS);
                        this.yEnd = k * (areaHeight / NUMTHREADS) + areaHeight / NUMTHREADS;

                        if (k == NUMTHREADS - 1)
                            this.yEnd = areaHeight;

                        this.yRange = this.yEnd - this.yBegin;

                        int[] part = new int[this.xRange * this.yRange];

                        for (int row = 0; row < this.yRange; ++row) {
                            for (int col = 0; col < this.xRange; ++col) {
                                if (!isBuilding)
                                    return null;
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
                    } catch (Throwable t) {
                        System.out.println(t);
                        throw new OutOfMemoryError("Not enough allocated storage");
                    }
                }

                protected void process(List<Integer> rows) {
                    if (isBuilding) {
                        rowsCompleted = rowsCompleted + rows.size();
                        int lastPercentage = percentageCompleted;
                        percentageCompleted = (int) Math.round(rowsCompleted * 100.0D / (double) areaHeight);
                        if (lastPercentage != percentageCompleted) {
                            onProgress.run(percentageCompleted);
                        }
                    }
                }

                public void done() {
                    try {
                        int[] part = (int[]) this.get();
                        if (part != null) {
                            int normalPartLength = areaWidth
                                    * ((k * (areaHeight / NUMTHREADS) + areaHeight / NUMTHREADS)
                                            - (k * (areaHeight / NUMTHREADS)));
                            System.arraycopy(part, 0, iterationData, normalPartLength * k, part.length);
                        }
                    } catch (ExecutionException | InterruptedException var3) {
                        String message = var3.getCause().getMessage();
                        if (message.equals("Java heap space")) {
                            throw new OutOfMemoryError("Not enough allocated storage");
                        }
                    }

                    ++finishedThreads;
                    if (finishedThreads == workerThreads.length) {
                        finishedThreads = 0;
                        isBuilt = true;
                        isBuilding = false;
                        onProgress.run(100);
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

        for (int i = 0; i < this.iterationData.length; ++i) {
            count += (long) this.iterationData[i];
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

    public void exportYAML(String path) throws FileNotFoundException, UnsupportedEncodingException {
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
        int fullWidth = Integer.parseInt(args[k + 1]);
        int fullHeight = Integer.parseInt(args[k + 2]);
        String outputPath = args[k + 3];
        try {

            Mandelbrot mand = Mandelbrot.fromYAMLFile(configFile, fullWidth, fullHeight);

            try {
                mand.exportYAML(configFile);
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
                str += "    [in progress] \r";
                System.out.print(str);

            }, () -> { // On finish

                // Print a full progress bar
                String str = "";
                for (int i = 0; i < PROGRESS_BAR_WIDTH; i++)
                    str += "#";
                System.out.print("|" + str + "| " + "100%   [done]  ");

                if (isVerbose) {
                    System.out.println("           ");
                    System.out.println();
                    System.out.println("> output file: " + outputPath);
                    System.out.println("> image size: " + mand.fullWidth + "x" + mand.fullHeight);
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
                    System.out.println("   - average number of iterations per pixel: " + (double) Math
                            .round((double) numIterationsTotal / (double) mand.iterationData.length * 100.0D) / 100.0D);
                } else {
                    System.out.println("> output: " + outputPath);
                }

                mand.exportImage(outputPath);

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

    public static Mandelbrot fromYAMLFile(String path, int fullWidth, int fullHeight)
            throws FileNotFoundException, MandelbrotConfigException {
        Yaml yaml = new Yaml();
        Map<String, Object> yamlData = null;
        InputStream inputStream = new FileInputStream(path);
        try {
            yamlData = (Map) yaml.load(inputStream);
        } catch (Exception e) {
            throw new MandelbrotConfigException("The yaml file was not found or the YAML data was corrupt");
        }
        return new Mandelbrot(yamlData, fullWidth, fullHeight);
    }

    @FunctionalInterface
    static interface Executable<T> {
        void run(T v);
    }

    static class MandelbrotConfigException extends RuntimeException {
        public MandelbrotConfigException(String message) {
            super(message);
        }
    
        public MandelbrotConfigException(Throwable cause) {
            super(cause);
        }
    
        public MandelbrotConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

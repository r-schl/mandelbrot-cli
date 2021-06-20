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
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.yaml.snakeyaml.Yaml;

public class Main {
    private static final String OUT_OF_MEMORY_ERR = "\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<";
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
    int progressBarWidth = 30;
    int finishedThreads = 0;
    long startTime;
    SwingWorker<int[], Integer>[] workers;
    int[] palette;
    int[] gradient;
    int color;
    static Map<String, Object> args;
    static String outputPath;
    static String configPath;

    public static void main(String[] arguments) {
        configPath = arguments[0];
        outputPath = arguments[1];
        Yaml yaml = new Yaml();

        try {
            InputStream inputStream = new FileInputStream(configPath);
            args = (Map) yaml.load(inputStream);
        } catch (FileNotFoundException var3) {
            System.err.println("> config file " + configPath + " was not found");
            System.exit(-1);
        }

        new Main();
    }

    public Main() {
        SwingUtilities.invokeLater(this::init);
    }

    void init() {
        this.width = (Integer) args.get("width");
        this.height = (Integer) args.get("height");
        this.zMinRe = (Double) args.get("minRe");
        this.zMinIm = (Double) args.get("minIm");
        this.zMaxRe = (Double) args.get("maxRe");
        this.zMaxIm = (Double) args.get("maxIm");
        this.nMax = (Integer) args.get("nMax");
        this.gradient = ((ArrayList) args.get("gradient")).stream().mapToInt(i -> i).toArray();
        this.color = (Integer) args.get("color");
        this.palette = this.createColorPalette(this.color, this.gradient, this.nMax);
        this.build(() -> {
            this.export(outputPath);

            try {
                Desktop.getDesktop().open(new File(outputPath));
            } catch (IOException var2) {
            }

        });
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
        } catch (OutOfMemoryError var6) {
            System.out.println("\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<");
            System.exit(-1);
        }

    }

    void build(final Runnable onFinish) {
        double arImage = (double) this.width / (double) this.height;
        double arComplex = Math.abs(this.zMaxRe - this.zMinRe) / Math.abs(this.zMaxIm - this.zMinIm);
        if (arImage != arComplex) {
            (new Exception("Aspect ratios are not the same")).printStackTrace();
            System.exit(-1);
        }

        this.startTime = System.currentTimeMillis();
        this.data = new int[this.width * this.height];
        this.workers = new SwingWorker[this.NUMTHREADS];

        for (final int i = 0; i < this.NUMTHREADS; ++i) {
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
                    this.yBegin = i * (height / NUMTHREADS);
                    this.yEnd = i * (height / NUMTHREADS) + height / NUMTHREADS;
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
                    rowsCompleted = rowsCompleted += rows.size();
                    double percentage = rowsCompleted * 100.0D / (double) height;
                    String progressStr = "";

                    while (progressStr.length() < progressBarWidth * (percentage / 100.0D))
                        progressStr += "#";
                    while (progressStr.length() < progressBarWidth)
                        progressStr += " ";

                    String str = "|" + progressStr + "| " + (double) Math.round(percentage * 10.0D) / 10.0D + "%";
                    str += percentage != 100.0D ? "   [in progress] \r" : "   [done]         \n";

                    System.out.print(str);
                }

                public void done() {
                    try {
                        int[] part = (int[]) this.get();
                        System.arraycopy(part, 0, data, part.length * i, part.length);
                    } catch (ExecutionException | InterruptedException var3) {
                        String message = var3.getCause().getMessage();
                        if (message.equals("Java heap space")) {
                            System.out.println(
                                    "\n>> An OutOfMemoryError occured. Please reduce the image size and try again. <<");
                            System.exit(-1);
                        }
                    }

                    ++finishedThreads;
                    if (finishedThreads == workers.length) {
                        finishedThreads = 0;
                        System.out.println("> output file: " + Main.outputPath);
                        System.out.println("> configurations (" + Main.configPath + "):");
                        System.out.println("   - picture dimension: " + width + "x" + height);
                        System.out.println(
                                "   - min complex number: " + zMinRe + (zMinIm > 0.0D ? "+" : "") + zMinIm + "i");
                        System.out.println(
                                "   - max complex number: " + zMaxRe + (zMaxIm > 0.0D ? "+" : "") + zMaxIm + "i");
                        System.out.println("   - max iterations: " + nMax);
                        System.out.println("   - set color: " + color);
                        System.out.println("   - color gradient: " + Arrays.toString(gradient));
                        System.out.println("> build information: ");
                        System.out.println("   - build time: "
                                + (double) (System.currentTimeMillis() - startTime) / 1000.0D + "s");
                        long numIterationsTotal = countTotalIterations();
                        System.out.println("   - total number of iterations: " + numIterationsTotal);
                        System.out.println("   - average number of iterations per pixel: "
                                + (double) Math.round((double) numIterationsTotal / (double) data.length * 100.0D)
                                        / 100.0D);
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
            if (zRe * zRe + zIm * zIm > 4.0D) {
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
}

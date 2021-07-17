
public class Main {

    public static void main(String[] args) {

        Mandelbrot m1 = Mandelbrot.fromYamlFile("standard.yaml");
        m1.build(() -> {
            m1.export("./lol.png");
        });

        Mandelbrot m2 = m1.getZoom(0, 0, 2);
        m2.build(() -> {
            m2.export("./lol2.png");
        });

    }
}

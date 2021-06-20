# mandelbrot-cmd

This is an application to run on the command line. 

You can get a picture of the Mandelbrot set by entering this command: 

```bash
java -jar mandelbrot-cmd.jar <configuration path> <output path>
```

The application reads the yaml data that is written to the specified configuration file and creates an image based on this data. This image is saved at the specified output path. 

For example:

```bash
java -jar mandelbrot-cmd.jar ./config.yaml ./a-nice-picture.png
```

The configuration file must be of type yaml and be structured as follows: 

```yaml
# Dimension of the image in px
width: 1000
height: 1000
# Area of the complex plane given by z_min and z_max
minRe: -1.5
minIm: -1.5
maxRe: 1.5
maxIm: 1.5
# Maximum number of iterations
nMax: 10000
# Color for points inside the mandelbrot set
color: 0x000000
# Color gradient for the points outside the mandelbrot set
# first color is for points that diverge after 1 iteration
# last color is for points that diverge after nMax-1 iterations
gradient: [0xFFFFFF, 0x0048ff, 0xffff00]
```


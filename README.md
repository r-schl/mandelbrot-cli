# mandelbrot-cmd

This is an application to run on the command line. 

You can get a picture of the Mandelbrot set by entering this command: 

```bash
java -jar mandelbrot-cmd.jar [OPTIONS] <configuration path> <picture width> <picture height> <output path>
```

The application reads the yaml data that is written to the specified configuration file and creates an image based on this data. The dimensions of the image must be specified in the command itself. If the aspect ratio of the complex number plane viewport does not match the ratio specified in the command, a background pattern is created. This image is saved at the specified output path. There are also optional arguments that can be added before specifying the configuration path: 

`-v` 	prints detailed information about the calculation (verbose)
`-o` 	opens the image after the calculation is completed

These arguments can also be combined. For example: `-vo` or `-ov`. 

A correct command might look like this:

```bash
java -jar mandelbrot-cmd.jar -vo ./config.yaml 500 500 ./aNicePicture.png
```

The configuration file must be of type yaml and be structured as follows: 

```yaml
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


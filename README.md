# mandelbrot-cli

### Overview

The Mandelbrot set is a set of complex numbers $c$ for which the function $f_c(z)=z^2+c$ does not diverge to infinity when iterated from $z=0$. If you represent each $c$ of this set in the complex number plane and assign a color value to each depending on whether and after how many iterations it becomes clear that the function $f_c(z)=z^2+c$ diverges towards infinity, a picture emerges. This application creates such an image based on configurations written in a file. 

### How to use this application

This is an application to run on the command line. 

You can get a picture of the Mandelbrot set by executing this application with following arguments: 

```bash
[OPTIONS] <configuration path> <picture width> <picture height> <output path>
```

The application reads the yaml data that is written to the specified configuration file and creates an image based on this data. The dimensions of the image must be specified in the command itself. If the aspect ratio of the complex number plane view port does not match the ratio specified in the command, a background pattern is created. This image is saved at the specified output path. There are also optional arguments that can be added before specifying the configuration path: 

`-v` 	prints detailed information about the calculation (verbose)
`-o` 	opens the image after the calculation is completed

These arguments can also be combined. For example: `-vo` or `-ov`. 

### Example interaction

A correct command might look like this:

```bash
java -jar mandelbrot-cmd.jar -vo ./config.yaml 3000 3000 ./aNicePicture.png 
```

The configuration file must be of type yaml and be structured as follows: 

```yaml
# Area of the complex plane given by two numbers 'min' (lower left corner) and 'max' (upper right corner)
minRe: -1.5
minIm: -1.5
maxRe: 1.5
maxIm: 1.5
# Maximum amount of iterations (iteration depth)
nMax: 10000
# Color (hexadecimal representation) for points inside of the mandelbrot set
innerColor: 0x000000
# Color gradient (hexadecimal representation) for points outside of the mandelbrot set
# > First color for n_max-1 iterations reached
# > Last color for 0 iterations reached
# At least one color must be specified
colorGradient: [0xFFFFFF, 0x0048ff, 0xffff00]
```

![alt text](./aNicePicture.png)
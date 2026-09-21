# assets/

## `car.png` — the car outline on the driving screen

Drop your own drawing in here as `car.png` and it replaces the placeholder outline that
`DashView.drawCar()` draws. No code change is needed: the screen loads this file if it exists
and falls back to the vector placeholder if it does not.

## Replacing it

Do not hand-edit the PNG. Draw however you like -- light strokes on a dark background, saved
as a JPEG, is fine and is what the current one started as -- and run the converter:

```
javac -d build/tools tools/MakeCarAsset.java
java -cp build/tools MakeCarAsset my-drawing.jpg assets/car.png
```

It keys the dark background out by turning brightness into opacity, subtracts a floor first so
JPEG noise across the black does not survive as a dirty rectangle, tints the strokes to the
screen's cyan while keeping the brightest cores near white, crops to the content, and scales
the result down to something the app will actually draw.

**Format, if you would rather prepare the file yourself**

- PNG with a **transparent background**, so the pool of light, the arcs and the starfield stay
  visible behind it.
- Drawn in the screen's cyan (`#3FD2FF`) or close to it. The image is blitted as-is and is not
  tinted at runtime, so its own colours are what you get.
- Roughly **400 × 480** is plenty. It is scaled to fit a box about 190 × 220 on the panel,
  preserving aspect ratio and centred, so anything larger is wasted memory and anything much
  smaller will look soft.
- Keep it under about 1 MB. It is decoded once into the static layer and then released, and
  oversized images are downsampled on load rather than held at full size.

The car sits in the middle of the screen with the four tyre callouts pointing at its corners.
Those anchor points are placed at fractions of the car box, so a drawing whose wheels sit in
roughly the usual places will line up without any adjustment.

**Checking it before driving anywhere**

```
bash tools/preview.sh
```

renders the whole screen to `build/preview/*.png` at the real 800 × 480, using this file if it
is present.

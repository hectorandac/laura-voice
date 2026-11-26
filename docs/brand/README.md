# App icon

Laura's icon is a render of her real 3D avatar (the same three.js model the app shows), front view with orthographic projection, flat lighting and a thin rose rim, on a warm apricot-to-cream plate. Each launcher density is rendered at its own pixel size from the model and area-downsampled, so she stays crisp at 48 and 24 dp.

- `icon-512.png`: store-size icon.
- `icon-preview.png`: the icon at 192/96/48/24 px in circle, squircle, rounded-square and teardrop masks on light, dark and photo wallpapers.

The Android resources live in `clients/android/app/src/main/res` (adaptive icon: gradient background layer, per-density foreground PNGs, traced monochrome layer) and `clients/wear/src/main/res`.

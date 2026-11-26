# Laura app icon

Sheets: `final.png` (the picked icon: layers, launcher sizes, themed, notification, watch), `concepts.png` (A, B and C side by side), `ic_launcher-512.png` (Play Store, unmasked).
Source: `src/gen.py` writes both the sheets and the Android drawables from one geometry (`python3 src/gen.py` for the sheets, `python3 src/gen.py android <repo>` for the drawables, `src/render.sh` to rasterize a sheet).

## The debate (face, hair, light)
- **Face:** the icon has to be *her*, not a logo of an assistant. People react to the face: two dark pill eyes with a catchlight, a little blush, a shy closed smile. At 48 px the eyes are the only feature that survives, so they must be large and must sit on porcelain with nothing hair-coloured under the chin (the beard lesson from rounds 4 to 6).
- **Hair / silhouette:** at 48 px and in the themed icon the face is gone and only the outline is left. Her outline is the blunt bob plus the wine side tail on one side; that asymmetric second shape is what made the avatar read as a girl and not an egg (`iter-6/16_silhouettes.png`). The icon should be that outline first and fill it in second.
- **Light / material:** she is a lantern, so the warmth should come from light, not from a sticker. Warm glow at the hairline, a single gloss on the crown, and a warm ground so the plum hair has contrast on both dark and light wallpapers (plum on the app's night background is too close in value at 48 px).

## Concepts
- **A. Voxel portrait (face lane):** her front view rebuilt as a 22 x 18 voxel grid on the dark lantern glow. Most faithful to the avatar, but busiest at 48 px, and the dark plate makes the plum hair muddy on dark launchers.
- **B. Bob and tail (hair lane):** flat vector; the bob, side locks and wine tail are one outline, the porcelain face is a window with pill eyes, blush and a small smile, the fringe edge is voxel-stepped, and the ground is a warm lantern glow. Fewest parts.
- **C. Lantern cube (light lane):** an isometric porcelain voxel with a plum cap, the face on the front plane and amber glow at the hairline seam. The most "voxel", but the face is skewed and small, and the cube looks like a box, not a person, in the themed icon.

## Pick: B
- It reads at 48 px in both masks and on both wallpapers: the bob and tail outline, two eyes and the warm ground still come through (`final.png`, right-hand 48 px cells).
- The monochrome layer is the best of the three: the hair frame and tail as ink, the face left open with the eyes as islands. It still looks like her in the tinted themed icon and as the 24 dp notification glyph.
- It carries all three lanes: the face lane's eyes, blush and smile, the hair lane's silhouette and stepped fringe, the light lane's hairline glow (the face warms toward the fringe), crown gloss and lantern ground.
- Nothing sits under the chin: the side locks stop at the jaw line and the background shows under the face.
- It is not a brand mark: no letterform, no speech bubble or mic; a voxel-cut bob and a side tail on a warm ground.

## Implementation
- `clients/android/app/src/main/res/drawable/ic_launcher.xml` and `ic_launcher_round.xml`: adaptive icon (background, foreground, monochrome). minSdk is 29, so no PNG fallbacks are needed.
- `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`: 108 dp vector layers. The tail tip stays within the 72 dp visible circle; the face and eyes are within the 66 dp safe zone.
- `clients/android/core/src/main/res/drawable/ic_laura.xml`: the notification small icon, a white 24 dp glyph (hair frame open at the chin, eye islands, side tail).
- `clients/wear/src/main/res/drawable/ic_launcher.xml`: the watch icon, the same art pre-clipped to a circle as a 48 dp vector.
- The app manifest gains `android:roundIcon="@drawable/ic_launcher_round"`; the other icon names are unchanged.

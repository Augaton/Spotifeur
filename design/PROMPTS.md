# Prompts for image generation

Five image prompts (logo, banner, three mockups) and one Sketch prompt to send after each image, in the same conversation. Order: logo first, the banner needs the logo. The identity behind them is in [IDENTITY.md](IDENTITY.md).

Paths are relative to the repository root.

---

## 1. Logo

**Attachments:** none.

```text
Design a beautiful, modern app icon for "Spotifeur", an open-source Android music player for a Spotify library. Style: Google Material 3 Expressive, with real depth.

Concept: one bold glyph built from Material 3 Expressive shapes. Use a "cookie" shape (a soft scalloped circle with 9 or 12 rounded lobes, like Material 3's Cookie shape) as the main form, with a rounded play triangle or a short wavy sound line on top of it. One idea, readable at 48 px. No letters, no text.

Depth: build it from 2 or 3 stacked layers, each a step lighter than the one below: the background tile, the cookie, then the play/wave element. Each layer casts a soft, large, low-opacity shadow tinted dark green (#04281B) offset downward. Light comes from the top-left: every shape has a subtle linear gradient (lighter top-left, darker bottom-right) and may have a thin, soft highlight on its top edge. It should feel tactile and dimensional like Material 3 Expressive elevation, not photoreal: no glass, no chrome, no bevel, no grain, no neon glow.

Colors (only these):
- background tile: jade green gradient from #0F9D63 (top-left) to #0B4F35 (bottom-right)
- cookie shape: #6FE3A6 to #0F9D63 gradient
- play triangle or wave: #EAF6EF with a #B4F7D1 highlight
- optional single small accent: lavender #C9BEFF
- shadows: #04281B
Do not use Spotify's green #1DB954. It must not resemble Spotify's logo: no three curved bars, no circle with arcs.

Format: Android adaptive icon on a 1024 x 1024 square canvas. The background fills the whole canvas. The glyph is centered and stays inside the central 61% (adaptive-icon safe zone), so it works when masked to a circle, a squircle or a rounded square.

Deliver in one image: the full icon large, and beside it small previews masked as a circle and as a squircle, plus the monochrome version (glyph alone, flat, single color #0B1F15 on white, no gradient or shadow) used for Android themed icons.
```

## 2. Banner

**Attachments:**
- the logo PNG chosen in step 1 (full icon, 1024 × 1024).

```text
Design a 1280 x 640 banner for the GitHub repository of "Spotifeur", an open-source Spotify client for Android. It is the README header and the GitHub social preview. Use the attached logo exactly as it is: do not redraw, recolor or restyle it.

Content, and nothing else:
- the attached logo, about 220 px tall, with its own soft shadow
- the name "Spotifeur"
- one line under the name: "Open-source Spotify client for Android"
No slogan, no other text, no badges, no phone, no screenshots.

Typography: Google Sans Flex with fully rounded terminals (ROND 100). Name: weight 750, tracking -2%, color #EAF6EF. Line under it: weight 500, color #B9CABF, about 40% of the name's size.

Layout: logo in the left third, name and line to its right, vertically centered, margins of at least 96 px. Keep everything important inside the central 1200 x 600 so GitHub's crop does not cut it.

Background, Material 3 Expressive with depth: dark #0A100D. Two or three very large Material 3 Expressive shapes (a scalloped "cookie", a pill) in #0B4F35 and #0F9D63, stacked and partly off the edges, each with a soft gradient (lighter top-left) and a large soft shadow, so they read as layers at different heights. At most one small lavender #C9BEFF shape. A thin wavy line, like Material 3's wavy progress bar, in #6FE3A6 may run along the bottom. Calm and clean: no grain, no neon, no glass, no rainbow, no Spotify green #1DB954.
```

## 3. Mockup A: listening

**Attachments, in this order:**
1. `design/screens/home.jpg`
2. `design/screens/player.jpg`
3. `design/screens/lyrics.jpg`
4. `design/screens/queue.jpg`

```text
Create a 2400 x 1350 landscape presentation image of the Android app "Spotifeur", showing the four attached screenshots side by side, in the attached order from left to right.

The screenshots are real and must appear exactly as attached: do not redraw, retouch, translate, crop or change any text, cover art or interface inside them. Only scale them uniformly.

Phones: each screenshot sits in a simple modern Android phone with no brand: thin uniform black bezel (about 14 px at this scale), outer corner radius about 62 px, screen corner radius about 48 px, no notch, no side buttons. All four phones are the same size, about 1090 px tall, upright and facing the viewer, evenly spaced about 70 px apart, centered horizontally, near the top. Each phone casts a soft, large shadow (black, about 40% opacity, offset downward).

Under each phone, a centered label in Google Sans Flex with rounded terminals (ROND 100), weight 500, about 34 px, color #EAF6EF at 85% opacity. Labels, left to right: "Home", "Player", "Lyrics", "Queue".

Background, Material 3 Expressive with depth: dark #0A100D with very large, soft Material 3 Expressive shapes (scalloped "cookie" shapes, pills) in #0B4F35 and #0F9D63, stacked at different heights with soft gradients and large shadows, partly behind the phones and off the edges. At most one small lavender #C9BEFF shape. The background stays calm and darker than the screens. No other text, no logo, no slogan, no Spotify green #1DB954.
```

## 4. Mockup B: library

**Attachments, in this order:**
1. `design/screens/album.jpg`
2. `design/screens/playlist.jpg`
3. `design/screens/create.jpg`
4. `design/screens/library.jpg`

```text
Create a 2400 x 1350 landscape presentation image of the Android app "Spotifeur", showing the four attached screenshots side by side, in the attached order from left to right.

The screenshots are real and must appear exactly as attached: do not redraw, retouch, translate, crop or change any text, cover art or interface inside them. Only scale them uniformly.

Phones: each screenshot sits in a simple modern Android phone with no brand: thin uniform black bezel (about 14 px at this scale), outer corner radius about 62 px, screen corner radius about 48 px, no notch, no side buttons. All four phones are the same size, about 1090 px tall, upright and facing the viewer, evenly spaced about 70 px apart, centered horizontally, near the top. Each phone casts a soft, large shadow (black, about 40% opacity, offset downward).

Under each phone, a centered label in Google Sans Flex with rounded terminals (ROND 100), weight 500, about 34 px, color #EAF6EF at 85% opacity. Labels, left to right: "Album", "Playlist", "New playlist", "Library".

Background, Material 3 Expressive with depth: dark #0A100D with very large, soft Material 3 Expressive shapes (scalloped "cookie" shapes, pills) in #0B4F35 and #0F9D63, stacked at different heights with soft gradients and large shadows, partly behind the phones and off the edges. At most one small lavender #C9BEFF shape. Use the same background style as the previous mockup if there was one, but move the shapes so the two images are not identical. The background stays calm and darker than the screens. No other text, no logo, no slogan, no Spotify green #1DB954.
```

## 5. Mockup C: settings and search

**Attachments, in this order:**
1. `design/screens/settings.jpg`
2. `design/screens/appearance.jpg`
3. `design/screens/search.jpg`
4. `design/screens/menu.jpg`

```text
Create a 2400 x 1350 landscape presentation image of the Android app "Spotifeur", showing the four attached screenshots side by side, in the attached order from left to right.

The screenshots are real and must appear exactly as attached: do not redraw, retouch, translate, crop or change any text, cover art or interface inside them. Only scale them uniformly.

Phones: each screenshot sits in a simple modern Android phone with no brand: thin uniform black bezel (about 14 px at this scale), outer corner radius about 62 px, screen corner radius about 48 px, no notch, no side buttons. All four phones are the same size, about 1090 px tall, upright and facing the viewer, evenly spaced about 70 px apart, centered horizontally, near the top. Each phone casts a soft, large shadow (black, about 40% opacity, offset downward).

Under each phone, a centered label in Google Sans Flex with rounded terminals (ROND 100), weight 500, about 34 px, color #EAF6EF at 85% opacity. Labels, left to right: "Settings", "Appearance", "Search", "Track options".

Background, Material 3 Expressive with depth: dark #0A100D with very large, soft Material 3 Expressive shapes (scalloped "cookie" shapes, pills) in #0B4F35 and #0F9D63, stacked at different heights with soft gradients and large shadows, partly behind the phones and off the edges. At most one small lavender #C9BEFF shape. Use the same background style as the previous mockups if there were any, but move the shapes so the images are not identical. The background stays calm and darker than the screens. No other text, no logo, no slogan, no Spotify green #1DB954.
```

---

## 6. Sketch file (send after each of the five images, in the same conversation)

**Attachments, depending on the image:**

| After | Attach |
| --- | --- |
| Logo | nothing |
| Banner | the logo PNG from step 1 |
| Mockup A | `design/screens/home.jpg`, `player.jpg`, `lyrics.jpg`, `queue.jpg` |
| Mockup B | `design/screens/album.jpg`, `playlist.jpg`, `create.jpg`, `library.jpg` |
| Mockup C | `design/screens/settings.jpg`, `appearance.jpg`, `search.jpg`, `menu.jpg` |

The attachments let the Sketch file embed the original images instead of copies cut out of the generated picture.

```text
Now turn the image you just generated into a Sketch document that rebuilds the same composition as editable layers, and give me the .sketch file to download. If I attached files with this message, embed them as the image layers instead of cutting them out of your generated picture.

Requirements:
- A valid .sketch file for Sketch 2025.3 or later: a ZIP containing document.json, meta.json, user.json and pages/<page-id>.json in the current Sketch JSON format (meta.json with "version": 182, "compatibilityVersion": 99, "app": "com.bohemiancoding.sketch3"), with any embedded images under images/. It must open in Sketch without errors or missing-font warnings other than Google Sans Flex.
- One page with one artboard of exactly the image's size (logo 1024 x 1024, banner 1280 x 640, mockup 2400 x 1350).
- Every element is a native, editable Sketch layer, not one flattened picture: shapes as vector paths (shapePath, oval, rectangle with corner radii), the cookie shapes as real scalloped vector paths, gradients as gradient fills, shadows as Sketch shadows, blurs as Sketch blurs, text as live text layers in the font family "Google Sans Flex", and every screenshot or logo as an image layer or an image fill on a rounded rectangle, at the same position and size as in the image.
- Clear layer names and groups, for example "Background", "Shape / Cookie 1", "Logo", "Name", "Subtitle", "Phone 1 / Frame", "Phone 1 / Screen", "Label / Player".
- Add these colors to the document color swatches: #0A100D, #141C17, #202A24, #0F9D63, #6FE3A6, #B4F7D1, #0B4F35, #04281B, #C9BEFF, #EAF6EF, #B9CABF.
- Mark the artboard as exportable as PNG at 1x and 2x.
- For the logo only, add two more artboards of 1024 x 1024: "Foreground" (the glyph alone, with its depth, on a transparent background) and "Monochrome" (the glyph alone, flat, in #0B1F15, on a transparent background). These are the Android adaptive and themed icon layers.

If you cannot write a .sketch file directly, generate it with Python (zipfile and json) in your code environment and give me the resulting file. Also give me an SVG export of each artboard.
```

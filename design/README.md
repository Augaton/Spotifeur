# Design

- [IDENTITY.md](IDENTITY.md): fonts, colors, shapes and rules for every visual.
- [PROMPTS.md](PROMPTS.md): prompts for the logo, the banner and the mockups, and for turning each image into a Sketch file.
- `logo.svg`: the logo master (1254 × 1254, editable layers), and `logo.png`, a 1024 × 1024 export. The launcher icon in `app/src/main/res/drawable/ic_launcher_*.xml` and the README logo (`docs/assets/img/icon.png`, cropped to a circle) are made from it. The rounded corners of its square are not used: the launcher applies its own mask.
- `screens/`: app screenshots for the mockups, in English, with a neutral status bar. The example track is "GLUCKSMANN LOVE" by Wailtoad and the example album is "The Dark Side of the Moon" by Pink Floyd. A playlist owner's username is blurred.

## README artwork

[`spotifeur-preview.sketch`](spotifeur-preview.sketch) contains three exportable frames:

| Frame | Size | Export |
| --- | --- | --- |
| Banner | 1280 × 640 | [banner.png](../docs/assets/img/banner.png) |
| Listening: home, player, lyrics, queue | 2400 × 1350 | [preview-listening.png](../docs/assets/img/preview-listening.png) |
| Library: album, playlist, settings, appearance | 2400 × 1350 | [preview-library.png](../docs/assets/img/preview-library.png) |

The artwork uses native vector shapes, gradients, shadows, live text and the
existing vector logo. Screenshot layers embed the original website captures from
`docs/assets/img/screens/`. Their aspect ratio and content are preserved. The
frames support PNG export at 1× and 2×.

[`build-preview.js`](build-preview.js) rebuilds the document and the three PNGs
through Sketch's scripting API. It loads the bundled Google Sans Flex font in
the Sketch process, with `ROND 100` and the site's `wght 780` for the main name.
The font is at [`app/src/main/res/font/gflex_variable.ttf`](../app/src/main/res/font/gflex_variable.ttf).

From Sketch 2025.3 or later, replace the repository path in this command:

```sh
/Applications/Sketch.app/Contents/MacOS/sketchtool run-script \
  'eval(String(NSString.stringWithContentsOfFile_encoding_error(context.repository + "/design/build-preview.js", 4, null)))' \
  --context='{"repository":"/path/to/Spotifeur"}' \
  --without-activating=YES
```

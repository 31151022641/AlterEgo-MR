# AlterEgo MR store artwork

Matching branded artwork for the Meta Horizon Store. The existing headset and
camera mark is preserved from `../../icons/alterego-source.png`. These covers
identify the app; they are not screenshots or representations of in-app UI.

| File | Dimensions | Purpose |
| --- | --- | --- |
| `icon-512.png` | 512 × 512 | Store icon |
| `cover-landscape-2560x1440.png` | 2560 × 1440 | Landscape cover |
| `cover-square-1440x1440.png` | 1440 × 1440 | Square cover |
| `cover-portrait-1008x1440.png` | 1008 × 1440 | Portrait cover |
| `hero-3000x900.png` | 3000 × 900 | Hero cover |
| `mini-1080x360.png` | 1080 × 360 | Mini landscape |
| `universal-3000x3000.png` | 3000 × 3000 | Universal basic asset source for Meta's automatic cover generation |

All upload PNGs are opaque 24-bit RGB. Title text is kept away from the top and
bottom 20% of each cover. SVG layout sources are included, and `review-sheet.png`
is a local inspection sheet only. No slogans, feature claims or platform logos
are included.

Regenerate with Node.js and Sharp:

```powershell
node scripts/build-store-art.cjs
# Or provide the installed Sharp module directory as the next argument.
```

The composition uses Segoe UI with an Arial fallback. Install a matching typeface
when regenerating on another operating system. The original logo's generation
provenance is retained in `../../icons/README.md`.

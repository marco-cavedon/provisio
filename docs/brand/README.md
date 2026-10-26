# Provisio brand assets

The mark is a 2×2 grid of storage bins: one full, two draining, one dashed.
Read left to right, top to bottom, it is stock running down; the dashed bin is
the forecast reorder. *Provisio* means both foresight and supply.

## Files

| File | Use |
| --- | --- |
| `provisio-logo.svg` | Primary lockup, light backgrounds |
| `provisio-logo-dark.svg` | Lockup for dark backgrounds |
| `provisio-logo-mono.svg` / `-white.svg` | Single colour (print, overlays) |
| `provisio-mark*.svg` | Symbol only, same four colourways |
| `provisio-icon.svg`, `provisio-icon-512.png` | App icon with subtle gloss |
| `favicon.svg`, `favicon.ico` | Simplified 16 px grid version: no dashes, fill levels as opacity |
| `apple-touch-icon.png` | 180 px, square (iOS applies its own mask) |

The wordmark is outlined from Manrope ExtraBold (800), tracking −0.035 em,
so the SVGs do not depend on the font being installed. Manrope and Inter are
licensed under the SIL Open Font License.

## Rules

- Below 24 px use the favicon, not the full mark: the dashes and inner gaps disappear.
- The app icon is the same in light and dark themes; only the flat mark changes colour.
- Leave clear space around the lockup equal to half the mark's height.
- Do not use Camunda orange or MongoDB green alongside the logo.

## Palette

| Token | Light | Dark | Notes |
| --- | --- | --- | --- |
| Brand (graphics only) | `#2E95E6` | `#7CC4FA` | 3.2:1 on white: never for text |
| `primary.main` | `#0B6BC4` | `#7CC4FA` | Light: white text 5.4:1. Dark: set `contrastText` to `#0B1724` (9.6:1) |
| `primary.dark` | `#08508F` | — | Hover / pressed |
| `primary.light` / selection | `#E6F3FD` | `#163654` | Primary on top: 4.75:1 / 6.6:1 |
| `background.default` | `#F5F9FD` | `#0B1724` | |
| `background.paper` | `#FFFFFF` | `#13263A` | |
| `text.primary` | `#0F2233` | `#E6EEF5` | 16.2:1 / 15.4:1 |
| `text.secondary` | `#5B6B7A` | `#9DB0C2` | 5.5:1 / 8.1:1 |
| `divider` | `#DCE7F1` | `#22384F` | |

Contrast ratios are WCAG 2.x, against `background.default` unless stated.

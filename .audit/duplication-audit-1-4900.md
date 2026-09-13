# Duplication / generalization audit — `TimMonetHooks.kt` lines 1–4900

**Read coverage (explicit):** I read lines **1–4900 contiguously** in 7 passes
(1–400, 400–1099, 1100–1799, 1800–2499, 2500–3199, 3200–3949, 3950–4699, 4700–4900).
Outside that band I additionally read only the definitions that in-scope code calls, and I mark
each of those explicitly as **out-of-scope read**: `sampleBitmapColorOfDrawable` (5371–5376),
`gradientColorOf` (5379–5402), `findRowCardColor` head (5408–5419), `sampleBitmapColor` (6665–6692),
`renderSample` (6695–6715), `colorLuma` (7358–7363), `rasterizeIconUniform` (8706–8814),
`tintAnyDrawableImpl` head (6353–6372). Every other line number below is inside 1–4900.
No file was modified.

File total: 9852 lines. In-scope band: 4900 lines, 141 `private fun` definitions.

---

## Category 1 — "render Drawable → Bitmap → sample pixels → classify light/dominant → replace with palette surface color"

### 1.1 `computeBrandLogoV2 (483–557)` — the canonical, most complete instance
Renders the drawable at intrinsic size and **rewrites every pixel in place**, classifying each pixel as
"blue→primary / black→onSurface" via a two-centroid distance blend.

```kotlin
488:  val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
490:  val saved = Rect(drawable.bounds)
491:  drawable.setBounds(0, 0, iw, ih)
492:  drawable.draw(canvas)
493:  drawable.bounds = saved
503:  val px = IntArray(iw * ih)
504:  bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
515:  val mx = maxOf(r, g, b)          // chroma = mx - mn
520:  if (mx - mn >= 40 && !(b >= r && b >= g)) continue     // "colourful" test
522:  if (r > 200 && g > 200 && b > 190) {                   // "is light/near-white" test
529:  val db = ((r - 0x21) * (r - 0x21) + (g - 0x70) * (g - 0x70) + (b - 0xFF) * (b - 0xFF)).toDouble()
531:  val dk = (r * r + g * g + b * b).toDouble()
533:  val t = db / (db + dk)
540:  bmp.setPixels(px, 0, iw, 0, 0, iw, ih)
```
Overlaps: `computeBrandLikeImage` (583–637), `rasterizeIconColor` (3118–3160), `glyphStats` (4759–4793),
`glyphColorStats` (4815–4860), **out-of-scope** `rasterizeIconUniform` (8706–8814).
Collapse estimate: the render+sample preamble (lines 485–504) plus the classification tail is
≈ **55 lines** of the 75 in this function.

### 1.2 `computeBrandLikeImage (583–637)` — dominant-colour classification into 3 buckets
Same preamble, plus an explicit **downscale** step that the others lack, and a 3-way pixel classifier.

```kotlin
587:  if (iw <= 0 || ih <= 0 || iw > 700 || ih > 700) return false
590:  val longSide = maxOf(iw, ih)
591:  if (longSide > 160) { val scale = 160f / longSide; iw = maxOf(1,(iw*scale).toInt()); ... }
597:  if (iw * ih < 3000) return false                       // early exit before rendering
598:  val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
600:  val saved = Rect(drawable.bounds)
601:  drawable.setBounds(0, 0, iw, ih)
602:  drawable.draw(canvas)
603:  drawable.bounds = saved
604:  val px = IntArray(iw * ih)
605:  bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
619:  if (b > 120 && b > r + 40 && b > g + 10) { blue++ }    // hue test
621:  } else if (mx - mn < 50) { mono++ }                    // achromatic test (≠40 in 1.1)
632:  blue * 10 >= tot * 3 && mono * 10 >= tot * 1 && colorNoise * 10 <= tot * 4
```
Overlaps with 1.1 at **488–504 / 598–605 are byte-identical except the alpha gate and size cap**.
Collapse estimate: **≈ 40 of 55 lines** (585–605 is a pure copy of 485–504).

### 1.3 `rasterizeIconColor (3118–3160)` — render → per-pixel luma → recolour with palette colour
Third copy of the identical preamble, then an inline BT.601 luma (see Category 5).

```kotlin
3128:  val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
3130:  val saved = Rect(drawable.bounds)
3131:  drawable.setBounds(0, 0, iw, ih)
3132:  drawable.draw(canvas)
3133:  drawable.bounds = saved
3134:  val px = IntArray(iw * ih)
3135:  bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
3145:  val lum = (((src ushr 16) and 0xFF) * 299 + ((src ushr 8) and 0xFF) * 587 + (src and 0xFF) * 114) / 1000
3147:  val k = 0.35f + 0.65f * (lum / 255f)
3154:  val out = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
3155:  out.setPixels(px, 0, iw, 0, 0, iw, ih)
3156:  BitmapDrawable(Resources.getSystem(), out)
```
Overlaps with **out-of-scope `rasterizeIconUniform` (8706–8814)**, which is the same function modulo
the colour policy:
```kotlin
8730:  val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
8732:  val saved = Rect(drawable.bounds)
8733:  drawable.setBounds(0, 0, iw, ih)
8734:  drawable.draw(canvas)
8735:  drawable.bounds = saved
8736:  val px = IntArray(iw * ih)
8737:  bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
8748:  if (maxOf(r0,g0,b0) - minOf(r0,g0,b0) > 60) colorful++     // same chroma classifier as 1.1/1.2
8752:  if (colorful * 100 > core * 40) return null
8781:  if ((r0*299 + g0*587 + b0*114) / 1000 < 90) darkPx++       // 3rd inline luma copy
8783:  val flatDark = darkPx * 10 >= core * 9
8800:  bmp.setPixels(out, 0, iw, 0, 0, iw, ih)
8801:  val nd = BitmapDrawable(Resources.getSystem(), bmp)
8802:  nd.setBounds(saved)
```
Both take a `Drawable`, both cap at 240px, both bail if the opaque core is tiny (`core < 4` at 3153,
`core < 8` at 8750), both produce `BitmapDrawable(Resources.getSystem(), bmp)`.
**The only real difference is the colour-selection policy (`k`-scaled single colour vs. dark/light lerp).**
Collapse estimate: **≈ 45 lines** (a single `rasterizeIcon(drawable, colorPolicy: (Int,Int)->Int)` would
absorb both).

### 1.4 `glyphStats (4759–4793)` — render → count "warm" pixels, no colour decision
```kotlin
4768:  if (iw <= 0 || ih <= 0 || iw > 200 || ih > 200) return null
4769:  val bitmap = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
4771:  val saved = Rect(bounds)
4772:  drawable.setBounds(0, 0, iw, ih)
4773:  drawable.draw(canvas)
4774:  drawable.bounds = saved
4776:  val px = IntArray(total)
4777:  bitmap.getPixels(px, 0, iw, 0, 0, iw, ih)
4782:  if (a < 110) continue
4787:  if (r > g && g >= b && (r - g) >= 40 && (g - b) >= 25 && (r - b) >= 90) warm++
4789:  intArrayOf(warm, core, total)
```

### 1.5 `glyphColorStats (4815–4860)` — render → average luma + dark/colourful counts
```kotlin
4824:  if (iw <= 0 || ih <= 0 || iw > 400 || ih > 400) return null
4826:  if (longSide > 120) { val s = 120f / longSide; iw = maxOf(1,(iw*s).toInt()); ... }   // same downscale as 1.2
4831:  val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
4833:  val saved = Rect(drawable.bounds)
4834:  drawable.setBounds(0, 0, iw, ih)
4835:  drawable.draw(canvas)
4836:  drawable.bounds = saved
4837:  val px = IntArray(iw * ih)
4838:  bmp.getPixels(px, 0, iw, 0, 0, iw, ih)
4850:  val luma = (r * 299 + g * 587 + bl * 114) / 1000     // 2nd inline luma copy in scope
4852:  if (luma < 110) dark++
4853:  if (maxOf(r, g, bl) - minOf(r, g, bl) > 40) colorful++  // same chroma classifier as 1.1
4856:  intArrayOf(core, dark, colorful, (lumaSum / core).toInt())
```
1.4 and 1.5 are **the same function with a different accumulator tuple**: identical structural prologue
(4761–4777 vs 4817–4838), identical `a < 110` alpha gate (4782 / 4845), identical `core < N → null` guard
(4855), different statistics. Collapse estimate: **≈ 25 lines** of the 46+35.

### 1.6 Call sites that turn the sample into an "is it light?" decision (the decision is duplicated, not the render)
- `dialogMonetizePass (845–978)` @ **963–973**: samples and compares to a threshold —
  ```kotlin
  963:  val dom = runCatching { sampleBitmapColorOfDrawable(d2) }.getOrNull() ?: return@walkViewTree
  966:  val op2 = dom or 0xFF000000.toInt()
  967:  if (colorLuma(op2) < 160) { d2.mutate(); d2.setColorFilter(target, SRC_IN); d2.setTint(target) }
  ```
- `tintEmoButtonIn (1063–1112)` @ **1086–1091** — **an exact copy of the same 5 lines**, same threshold 160, but the comparison is *inverted* because the guard is written as an early-out:
  ```kotlin
  1086: val dom = runCatching { sampleBitmapColorOfDrawable(d) }.getOrNull() ?: return@walkViewTree
  1089: val op = dom or 0xFF000000.toInt()
  1090: if (colorLuma(op) >= 160) return@walkViewTree
  ```
  These two are the **clearest copy-paste pair in the file** (both inside `walkViewTree(decor, 400)`,
  both gated on `iw in 1..96 && ih in 1..96` at 962 / 1083). Collapse estimate: **≈ 12 lines**.
- `tintBadgeDigitText (2055–2113)` @ **2073–2076**: same shape against `isPrimaryColor` instead of a
  luma threshold —
  ```kotlin
  2073: else -> d.colorFilter != null || runCatching {
  2074:     val dom = sampleBitmapColorOfDrawable(d) ?: return@runCatching false
  2075:     isPrimaryColor(dom)
  2076: }.getOrDefault(false)
  ```
- `solidColorOf (4582–4630)` @ **4620–4621, 4624** — routes to the sampling utilities (see Category 3).
- `hookMineGrid (639–767)` @ **703–736** is the **non-sampling member of the same family**: it reads the
  background colour via the `Drawable`-type ladder, converts to opaque, tests `colorLuma(...) >= 235`,
  then replaces with `scheme.surfaceBright`:
  ```kotlin
  705: if (rb is android.graphics.drawable.ColorDrawable) { rCol = rb.color }
  707: else if (rb is android.graphics.drawable.GradientDrawable) { rCol = runCatching { rb.color?.defaultColor }... }
  712: val alpha = (rCol ushr 24) and 0xFF
  714: if (rCol != 0 && alpha in 1..127 && colorLuma(opaqueCol) >= 235) {
  717:     val cardCol = scheme.surfaceBright
  ```
  This is the same "near-white (≥235) → surfaceBright" rule as `forceProfilePageCards` @ **4537**,
  `recolorContainer` @ **4640**, `hookProfileContentCard` @ **3836–3842** — four independent
  implementations of one rule (see Category 5).

### 1.7 `hookQuiBadge (4699–4751)` @ **4725–4733** — chroma-classification instead of sampling
```kotlin
4726: val r = (result shr 16) and 0xFF ; val g = ... ; val b = ...
4729: if (maxOf(r, g, b) - minOf(r, g, b) <= 30) { scheme.secondaryContainer } else { scheme.primary }
```
Same "is this colour achromatic?" decision as **1.2 line 621** (`mx - mn < 50`), **1.1 line 520**
(`mx - mn >= 40`), **1.6 line 4853** (`> 40`), and `isErrorRed` @ **194–200**. Five different
thresholds (30 / 40 / 40 / 50 / `abs(g-b) <= 30`) for one concept.

---

## Category 2 — "walk the view tree and tint descendants": every walker with its style, guard and job

### 2.1 The one shared walker — `walkViewTree (1209–1227)`
```kotlin
1209: private inline fun walkViewTree(root: View, maxNodes: Int, crossinline visit: (View) -> Unit) {
1214:     val stack = java.util.ArrayDeque<View>() ; stack.add(root)
1216:     var guard = 0
1217:     while (stack.isNotEmpty() && guard < maxNodes) {
1218:         val v = stack.removeFirst() ; guard++
1220:         if (v is ViewGroup) { for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i)) }
1225:         visit(v)
```
Note: despite the doc comment claiming "visit 返回 true 表示已处理并跳过其子树", the implementation
**cannot skip subtrees** — children are pushed at 1220–1224 *before* `visit(v)` runs at 1225. Every
caller that wants "skip subtree" must emulate it with `return@walkViewTree`, which still walks the
already-pushed children. This is a correctness/API mismatch, not just duplication.

**14 call sites**, all identical in shape, differing only in the node cap:
| call site | cap | what the lambda does |
|---|---|---|
| 879 | 400 | preview-body text → onSurfaceVariant (dialog) |
| 942 | 400 | hint + ≤96px dark icons → onSurfaceVariant (dialog) |
| 1069 | 400 | emo/keyboard toggle icons → onSurface |
| 1457 | 48 | register panel-item drawables (`markPanelItem`) |
| 1709 | 32 | all TextViews → onSurface (`tintTexts`) |
| 1776 | 200 | panel icons rasterised before draw (`hookPanelDispatch`) |
| 1916 | 48 | panel icons rasterised at bind (`hookPlusPanelIcons`) |
| 2186 | 3000 | RecyclerView `notifyDataSetChanged` + QUIBadge refresh |
| 2270 | 600 | login page bg/input-host recolour (`loginPageMonetizePass`) |
| 2389 | 400 | quick-menu bg/text/icon recolour (`forceMonetQuickMenu`) |
| 2995 | 200 | reply jump-arrow icons (`applyReplyJumpIcon`) |
| 3229 | 400 | reply icon inventory dump (`logReplyIconInventory`) |
| — | — | (plus 6057, 30 — **out-of-scope read**) |

### 2.2 Ad-hoc BFS walkers that re-implement 2.1 by hand
| function | lines | style | depth/node guard | work |
|---|---|---|---|---|
| `insideProfileRootTree` | 4035–4056 | hand-rolled BFS, `ArrayDeque` | `count < 600` (4045) | climb to root (4036–4041) then BFS for class name containing `profilecard` (4048) |
| `fixProfileRowTexts` | 4451–4484 | hand-rolled BFS | `guard < 200` (4456) | TextViews with low-alpha/low-luma → onSurfaceVariant / `mapPopupTextColor` (4464–4482) |
| `forceProfilePageCards` | 4488–4565 | hand-rolled BFS + parent climb | `count < 300` (4504) | climb to `PullToZoomHeaderListView` (4491–4499), then per-BG recolour dispatch (4529–4562) |
| `forceMonetQuickMenu` layer scan | 2501–2554 | hand-rolled BFS **inside** an already-walked function | `c2 < 1500` (2507) | second pass over `root.rootView` for LayerDrawable/StateList backgrounds (2517–2549) |

`insideProfileRootTree` (4042–4053), `fixProfileRowTexts` (4453–4463) and `forceProfilePageCards`
(4501–4528) are **structurally identical loops** — same 8-line body, different guard and lambda:
```kotlin
// 4042-4053
val stack = java.util.ArrayDeque<View>() ; stack.add(root)
var count = 0
while (stack.isNotEmpty() && count < 600) {
    val current = stack.removeFirst() ; count++
    if (current.javaClass.name.contains("profilecard")) return true
    if (current is ViewGroup) { for (i in 0 until current.childCount) stack.addLast(current.getChildAt(i)) }
}
// 4453-4463  (guard 200, then `if (view is TextView) {...}`)
// 4501-4528  (guard 300, then dump + bg recolour, plus `if (current !== listRoot)`)
```
Collapse estimate: **≈ 30 lines** (each 12–16-line prologue → one `walkViewTree(root, N) { }` call).

### 2.3 Recursive (DFS) walkers
| function | lines | recursion shape | guard | work |
|---|---|---|---|---|
| `forceMonetSubtree` (inner) | 1237–1256 | `for (i in 0 until view.childCount) forceMonetSubtree(...)` (1241–1243), **children first, then self** | none | text colour via `mapPopupTextColor` (1248–1254), background via `tintAnyDrawable` (1255) |
| `forceTimelineText` | 2758–2778 | identical child loop (2763–2776) | none | `forceBubbleColor` + `setTextColor(onPrimary)` by hard-coded ids (2767–2774) |
| `recolorTextViews` | 3411–3429 | identical child loop (3423–3428) | none | setTextColor/setLinkTextColor + strip `ForegroundColorSpan` (3413–3422) |
| `firstTextViewColor` | 3347–3358 | DFS with early return (3350–3356) | none | first `currentTextColor` found |
| `hookMineGrid`'s local `walk` | 738–759 | local recursive `fun walk(v: View)` (754–758) | none | `SRC_IN` + tint all `ImageView` drawables to primary |
| `forceMonetSubtree` (outer) | 1229–1234 | 2-line wrapper: resolve dark+scheme, delegate | — | — |

Lines 1241–1243, 2763–2776, 3423–3428 and 754–758 are **the same four-line `ViewGroup` child loop**
written four times. `hookMineGrid` even declares an inner `fun walk` at 738 when `walkViewTree` already
exists in the same file.

### 2.4 Parent-chain (upward) walkers — 23 `parent as? View` loops in scope
| function | lines | max depth | predicate / action |
|---|---|---|---|
| `dialogMonetizePass` img-sibling scan | 904–916 | 3 | looks for an `ImageView` sibling up to 3 levels |
| `isInPanelItem` | 1467–1477 | 8 | membership in `panelItemViews` |
| `isInProfileCardUi` | 1626–1638 | 8 | class name contains ProfileCard/ProfileHeader |
| `replyBlockRoot` | 2917–2927 | 4 (`repeat(4)`) | first ancestor with "Reply"; bails on RecyclerView |
| `stashReplyBlockColor` | 2934–2944 | 16 | write colour into every "Reply" ancestor |
| `replyBlockColorOf` | 2947–2961 | 16 | read colour from nearest "Reply" ancestor |
| `replyBlockAncestor` | 2964–2975 | 16 | nearest "Reply" ancestor |
| `bubbleHost` | 3385–3404 | 16 | scheme-colour match on each ancestor's background |
| `isProfileActivityView` | 4015–4032 | 8 | walk `ContextWrapper.baseContext` for Activity name |
| `insideProfilePage` | 4059–4068 | 20 | ancestor class name contains `profilecard` |
| `isProfileContent` | 4439–4448 | 12 | ancestor `isInstance(cls)` |
| `forceProfilePageCards` climb | 4491–4499 | unbounded | find `PullToZoomHeaderListView` |
| `isPlusPanelHost` | 4798–4810 | 8 | pluspanel name, or QQViewPager at depth ≥3 |
| `handleImage` plate climb | 4898–4904 | 3 | `fixPlusItemPlate` on ancestors |

`replyBlockRoot` (2919–2926), `stashReplyBlockColor` (2937–2943), `replyBlockColorOf` (2950–2959) and
`replyBlockAncestor` (2967–2974) are **four copies of one loop**:
```kotlin
2937: while (cur != null && hops < 16) {
2938:     val name = cur.javaClass.name
2939:     if (name.contains("RecyclerView")) break
2940:     if (name.contains("Reply", ignoreCase = true)) replyBlockColors[cur] = color
2941:     cur = cur.parent as? View ; hops++
```
Collapse estimate: **≈ 30 lines** (one `ancestorSequence(view, maxHops) { }` / `firstAncestor {}` helper).

### 2.5 Total for Category 2
~**95–110 lines** of traversal boilerplate (≈30 from 2.2 BFS bodies + ≈20 from 2.3 child loops
+ ≈30 from 2.4 Reply loops + ≈12 from the duplicated `walkViewTree`-inside-`forceMonetQuickMenu` second
pass at 2504–2515). Additionally 14 different magic `maxNodes` values (30/32/48/48/200/200/300/400×5/
600/1500/3000) for the *same* walker with no documented rationale.

---

## Category 3 — duplicated bitmap-render / sample utilities

### 3.1 The render-to-bitmap preamble, verbatim, 6× (5 in scope)
| # | function | lines of preamble | bounds save | restore | notes |
|---|---|---|---|---|---|
| 1 | `computeBrandLogoV2` | 488–492 | `Rect(drawable.bounds)` | `drawable.bounds = saved` | 1000px cap (487) |
| 2 | `computeBrandLikeImage` | 598–602 | `Rect(drawable.bounds)` | `drawable.bounds = saved` | 700px cap + 160px downscale (587–595) |
| 3 | `rasterizeIconColor` | 3128–3132 | `Rect(drawable.bounds)` | `drawable.bounds = saved` | 240px cap, bounds fallback (3122–3126) |
| 4 | `glyphStats` | 4769–4773 | `Rect(bounds)` (bounds already read) | `drawable.bounds = saved` | 200px cap |
| 5 | `glyphColorStats` | 4831–4835 | `Rect(drawable.bounds)` | `drawable.bounds = saved` | 400px cap + 120px downscale (4826–4830) |
| 6 | **out-of-scope read** `renderSample` | 6701–6706 | `drawable.copyBounds()` | `drawable.bounds = saved` | 16px cap, `bitmap.recycle()` |
| 7 | **out-of-scope read** `rasterizeIconUniform` | 8730–8735 | `Rect(drawable.bounds)` | `drawable.bounds = saved` | 240px cap, `nd.setBounds(saved)` at 8802 |

The 4-line body `val bmp = Bitmap.createBitmap(iw, ih, ARGB_8888); val canvas = Canvas(bmp);
val saved = Rect(drawable.bounds); drawable.setBounds(0,0,iw,ih); drawable.draw(canvas);
drawable.bounds = saved` occurs at **488–493, 598–603, 3128–3133, 4769–4774, 4831–4836**, i.e. **5×
in scope**, plus 6701–6706 and 8730–8735. A single
`withDrawableRendered(d: Drawable, max: Int): Pair<Bitmap, IntArray>?`
(~10 lines) replaces ~**30 in-scope lines and ~42 lines overall**.

### 3.2 Pixel-read idioms
- Full-frame read: `val px = IntArray(iw*ih); bmp.getPixels(px, 0, iw, 0, 0, iw, ih)` at **503–504,
  604–605, 3134–3135, 4365–4366, 4776–4777, 4837–4838** (6× in scope) + 6699–6701, 8736–8737.
- Full-frame write: `bmp.setPixels(px, 0, iw, 0, 0, iw, ih)` at **540, 3155, 4432, 8800**.
- **A completely different sampling strategy** in the out-of-scope `sampleBitmapColor (6665–6692)`:
  it does **not** use `getPixels` at all, but 9 `getPixel` calls on a fixed stride:
  ```kotlin
  6670: val xs = intArrayOf(w / 4, w / 2, 3 * w / 4)
  6671: val ys = intArrayOf(h / 4, h / 2, 3 * h / 4)
  6679: if (pixel ushr 24 > 200) { r += ...; count++ }
  6688: (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
  ```
  So the file has **three distinct "average/dominant colour" strategies** for the same question:
  `sampleBitmapColor` (9-point stride average, 6665), `renderSample` (16px raster then 9-point average,
  6695), and `sampleBitmapColorOfDrawable` (5371–5376 → `BitmapDrawable` fast path else `renderSample`).
  **`sampleBitmapColorOfDrawable` (5371–5376) is 100 % redundant with `sampleBitmapColor`'s own
  null/type handling** — it only exists to avoid rasterising a `BitmapDrawable`, and `renderSample`
  already produces an equivalent answer for every other type.

### 3.3 No sampling-stride normalisation
`computeBrandLikeImage` uses `longSide > 160` (591), `glyphColorStats` uses `longSide > 120` (4826),
`renderSample` uses 16 (6699), `computeBrandLogoV2` uses no downscale at all (487 caps at 1000² = up to
4 MB `IntArray`, noted in the comment at 461–462). Four different sampling densities for the same
"average colour of a drawable" question.

---

## Category 4 — "get a colour out of a Drawable" helpers (unwrapping + reflection)

### 4.1 Two near-identical recursive unwrappers
`solidColorOf (4582–4630)` and `gradientColorOf (5379–5402, out-of-scope read)` answer the same
question with the same recursion over `GradientDrawable` / `LayerDrawable` / `DrawableContainer`:

```kotlin
// solidColorOf 4582-4630
4584: is GradientDrawable -> try { drawable.color?.defaultColor } catch (t: Throwable) { null }
4589: is ColorDrawable -> colorOfColorDrawable(drawable)
4590: is DrawableContainer -> { /* last non-null child first (4597-4606), then forward scan (4607-4609) */ }
4612: is LayerDrawable -> { for (i in 0 until drawable.numberOfLayers) solidColorOf(drawable.getDrawable(i))?.let { return it } }
4620: is BitmapDrawable -> sampleBitmapColor(drawable.bitmap)
4621: is NinePatchDrawable -> renderSample(drawable)
4623: if (drawable.javaClass.name.startsWith("com.tencent.theme.Skinnable")) renderSample(drawable)

// gradientColorOf 5379-5402
5380: if (drawable is GradientDrawable) { return try { drawable.color?.defaultColor } catch (t: Throwable) { null } }
5387: if (drawable is LayerDrawable) { for (i ...) gradientColorOf(...)?.let { return it } }
5393: if (drawable is DrawableContainer) { for (child in children) gradientColorOf(child)?.let { return it } }
```
`gradientColorOf` is exactly `solidColorOf` **minus** the `ColorDrawable`, `BitmapDrawable`,
`NinePatchDrawable` and Skinnable branches, **plus** a "forward scan children" order.
Collapse estimate: **≈ 18 of the 21 lines of `gradientColorOf`**, and it makes `bubbleHost` (3389)
inherit the `ColorDrawable` handling it currently lacks.

Same shape again, earlier in scope, in three more flavours:
- `hookMineGrid` **705–711** — inline `ColorDrawable` → `.color` / `GradientDrawable` → `.color?.defaultColor`.
- `loginPageMonetizePass` **2236–2261** — a `when (b)` with `ColorDrawable`, `GradientDrawable`, `else → SRC_IN`.
- `loginPageMonetizePass` **2292–2305** — the *identical* `when (hb)` three-arm ladder again, targeting
  `surfaceContainerHigh`.
- `loginPageMonetizePass` **2323–2337** — a third `when (b)` ladder in the same function computing `nearWhite`.
- `forceMonetQuickMenu` **2519–2537** — a fourth ladder (`ColorDrawable` / `GradientDrawable` /
  `LayerDrawable` / `StateListDrawable`) dispatching to `forceSolidDrawableColor`.
- `hookPanelDispatch`-adjacent `solidColorOf` callers at **3839, 4514, 4532, 4639**.

### 4.2 Five different colour-write dispatchers
`forceSolidDrawableColor (2561–2606)`, `recolorDrawable (4648–4666)`, `recolorButtonState (4100–4139)`,
`tintPillBackground (3525–3540)`, and the inline blocks at 705–725 / 2236–2261 / 2519–2537 all implement
"if GradientDrawable → setColor, else if ColorDrawable → setColor, else mutate+SRC_IN+tint". Examples:

```kotlin
// recolorDrawable 4648-4665
4650: is GradientDrawable -> { drawable.mutate(); drawable.setColor(MonetPalette.amoledBlack(scheme.surfaceBright)) }
4654: is ColorDrawable -> { drawable.mutate(); drawable.setColor(MonetPalette.amoledBlack(scheme.surfaceBright)) }
4658: else -> { drawable.mutate(); drawable.setColorFilter(..., SRC_IN); drawable.setTint(...) }

// recolorButtonState 4102-4137  (same 3 arms, + mStrokePaint/mStrokeColors reflection inside arm 1)
4103: is GradientDrawable -> { child.setColor(color); /* 4107 mStrokePaint, 4116-4127 mGradientState.mStrokeColors */ }
4132: is ColorDrawable -> child.setColor(color)
4133: else -> { child.setColorFilter(color, SRC_IN); child.setTint(color) }

// forceSolidDrawableColor 2563-2605  (same 3 arms + DrawableContainer/LayerDrawable recursion + invalidateSelf)
2563: if (drawable is GradientDrawable) { mutate; setColor; invalidateSelf; return true }
2569: if (drawable is ColorDrawable) { mutate; setColor; invalidateSelf; return true }
2597: try { mutate; setColorFilter(color, SRC_IN); setTint(color); invalidateSelf; return true }
```
`recolorButtonState` is a **strict superset** of `recolorDrawable` (it also fixes the stroke paint).
Collapse estimate: **≈ 35 lines** across 4.1+4.2.

### 4.3 `DrawableContainer` unwrap repeated 7× (all in scope)
`constantState as? DrawableContainer.DrawableContainerState` appears at **2578, 4085, 4200, 4218, 4569,
4591, 4634** — every one followed by `?: return` and `state.children ?: return`:
```kotlin
4085: val state = drawable.constantState as? DrawableContainer.DrawableContainerState ?: return
4087: val children = state.children ?: return
4088: val n = state.childCount
4089: for (i in 0 until n) { children[i]?.let { child -> recolorButtonState(child, ...) } }
```
and the near-twin at 4200–4208, at 4218–4223 (recursive instead of index-based), at 4569–4578
(last non-null child only), at 4634–4644 (forward, luma-gated).

### 4.4 Reflection on private drawable fields, 3 unrelated implementations
- `colorOfColorDrawable (116–119)` + the cached field at **105–107** — `ColorDrawable.mColor`, with a
  public-API-first fallback.
- `setJumpArrowColor (3168–3186)` — `mBitmapState` → `mPaint` → `paint.colorFilter = PorterDuffColorFilter(...)`.
- `hookUnreadBubble (4233–4352)` @ **4271–4283** — `mBitmap` field for `com.tencent.theme.SkinnableBitmapDrawable`.
- `tintAnyDrawableImpl` @ **6355–6369 (out-of-scope read)** — iterates `drawable.javaClass.declaredFields`
  for `RippleDrawable` children.
- `h: fixPlusItemPlate (1443–1452)` @ 1446 — `v.background !is DrawableContainer` as the sole recogniser.
Collapse estimate: **≈ 20 lines** (a `skinnablePaintOf(d)` / `skinnableBitmapOf(d)` pair).

---

## Category 5 — inline luma / "is this colour light" re-derivation instead of the shared helper

The shared helper exists: **out-of-scope read** `colorLuma (7358–7363)` =
`(r*299 + g*587 + b*114) / 1000`, plus `opaqueColor (1204)`. **24 in-scope call sites** use `colorLuma`
correctly (715, 949, 967, 1090, 1267, 1273, 2238, 2248, 2328, 2334, 2426, 2450, 2467, 2522, 2530, 3840,
4373, 4422, 4469, 4537, 4640, 4681) — but the following **re-derive it inline**:

| # | function | line | inline expression | equivalent to |
|---|---|---|---|---|
| 1 | `rasterizeIconColor` | **3145–3146** | `val lum = (((src ushr 16) and 0xFF)*299 + ((src ushr 8) and 0xFF)*587 + (src and 0xFF)*114)/1000` | `colorLuma(src and 0xFFFFFF)` |
| 2 | `glyphColorStats` | **4850** | `val luma = (r*299 + g*587 + bl*114)/1000` | `colorLuma(c)` |
| 3 | **out-of-scope read** `rasterizeIconUniform` | **8781** and **8794** | `(r0*299 + g0*587 + b0*114)/1000` (twice, two separate loops) | `colorLuma(c)` |
| 4 | `computeBrandLogoV2` | **515–539** | manual `mx`/`mn` + squared-distance blend instead of any luma helper | — |

And the **"near-white" threshold 235 is written four times with four different alpha-handling idioms**:
```kotlin
 714:  if (rCol != 0 && alpha in 1..127 && colorLuma(opaqueCol) >= 235)      // hookMineGrid, opaqueColor()
3840:  colorLuma(it or 0xFF000000.toInt()) >= 235                             // hookProfileContentCard
4537:  (solid != null && colorLuma(solid or 0xFF000000.toInt()) >= 235)       // forceProfilePageCards
4640:  if (colorLuma(solid or 0xFF000000.toInt()) >= 235)                     // recolorContainer
```
(`or 0xFF000000.toInt()` at 3840/4537/4640 is exactly what `opaqueColor()` at 1204 does — the helper is
bypassed three times.)

**Other magic "is light" thresholds for the same concept, all inline:** `>= 120` (2238, 2248),
`>= 140` (1273, 2426, 2450, 2530), `>= 150` (2522), `< 150` (1267), `< 160` (949, 967, 1090),
`>= 200` (2467), `>= 220` (2334), `< 70` (4681). **Eleven distinct cut-offs** for "light/dark enough to
recolour", none named, none documented.

**Chroma/achromatic thresholds, four values for one concept:** `<= 30` (4729), `> 40` (4853),
`>= 40` (520), `< 50` (621), plus `Math.abs(g - b) <= 30` (199).

---

## Summary of collapse potential inside lines 1–4900

| category | duplicated lines (in scope) | single abstraction |
|---|---|---|
| 1 sampled-image classification | ~**110** | `renderSample(...)` + `classifyPixels(...)` + one "near-white→surface" rule |
| 2 view-tree traversal | ~**95–110** | `walkViewTree` used everywhere; `firstAncestor/ancestorSequence` for the 23 upward loops |
| 3 render/sample utilities | ~**42** | `withDrawableRendered(d, max)` + one sampling strategy |
| 4 drawable→colour + colour→drawable | ~**75** | `solidColorOf` (folding `gradientColorOf`), `applyColor(d, color)` |
| 5 luma/light re-derivation | ~**25** | `colorLuma` everywhere + named thresholds (`isNearWhite`, `isDark`, `isAchromatic`) |
| **total** | **≈ 350–360 lines** | |

**Highest-value, lowest-risk fixes (evidence-ranked):**
1. `dialogMonetizePass` 963–967 vs `tintEmoButtonIn` 1086–1090 — literal copy-paste, 5 lines each.
2. `solidColorOf` (4582) absorbing `gradientColorOf` (5379) — removes a whole 21-line duplicate.
3. `replyBlockRoot` / `stashReplyBlockColor` / `replyBlockColorOf` / `replyBlockAncestor` (2917–2975) —
   four copies of one 16-hop "Reply" ancestor loop.
4. The `ArrayDeque`+guard prologue at 4042–4053, 4453–4463, 4501–4528, plus lines 2504–2515 — replace
   with `walkViewTree`.
5. `rasterizeIconColor` (3118) vs `rasterizeIconUniform` (8706) — one function with a colour-policy lambda.

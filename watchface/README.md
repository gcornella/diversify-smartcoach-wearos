# Diversify Watch Face 

> I created a watch face for Samsung Wear OS (Android) enhancing user-experience and leveraging human factors design to digital health and stroke rehabilitation. It’s a clean, complication-driven face (Android Watch Face Format XML + custom providers) that supports an app counting upper-extremity movement, with a progress ring for the daily goal and a tap target suggesting proposed exercises.
A watch face is the home screen of a smartwatch. On Wear OS, it renders time/date and can surface **live data “complications”**; small UI regions powered by system or app-provided data sources (steps, goals, battery, custom metrics, etc.). A well-designed watch face lets users glance key information with **zero navigation** and **near-zero cognitive load**.

---

## Preview (add your images later)

> *Add screenshots or GIFs here once you build/deploy*

* `docs/screenshot_active.png` — Active mode
* `docs/screenshot_ambient.png` — Ambient mode
* `docs/screenshot_colors.png` — Theme variants

---

## Why complications? (And why this matters for stroke recovery)

Complications let us show **one or two critical metrics** with strong visual affordances, instead of burying information in menus. For people in stroke rehabilitation, this is especially important:

* **Good UI:** fewer items, bigger type, high contrast.
* **Progress cues:** **RANGED_VALUE** progress rings provide immediate “how far am I?” feedback without reading, which has been proven to be beneficial in rehabilitation..
* **Plain language labels:** **SHORT_TEXT** lets us annotate the metric (“active”, “wear time”, “battery”).
* **Ambient clarity:** simplified elements in ambient mode reduce visual clutter while preserving status and saving battery.

---

## Complications in this watch face
This repo’s example emphasizes: **one primary progress ring**, **two supporting rings**, **one circular label**, and large, readable time/date.

* `watchface.xml` — a complete **Watch Face Format** XML that defines:

    * A **RANGED_VALUE** progress ring (e.g., “active movement minutes”, “therapy goal”, etc.).
    * A **SHORT_TEXT** left complication (e.g., emoji + label such as “exercises”).
    * A **RANGED_VALUE** top complication for **wear time** (minutes/hours on wrist).
    * A **RANGED_VALUE** right complication for **battery** indicator.
    * Large, legible **digital time** and **date**.
    * Color **theme configuration** users can select.

You can plug in your own complication providers or use the defaults included.

### RANGED_VALUE

* **What it is:** Numeric value between **min** and **max**; perfect for goals and progress.
* **How it renders here:** **Circular progress rings** with labels, plus value readouts (e.g., “2h15’”).
* **Where used:**

    * **Slot 0**: Full-screen main progress (**MyProgressComplicationProviderService**).
    * **Slot 2**: **Wear time** ring (**MyWearTimeComplicationProviderService**).
    * **Slot 3**: **Battery** ring (**System: WATCH_BATTERY** by default).

### SHORT_TEXT

* **What it is:** Brief text string (optionally with an icon/emoji).
* **How it renders here:** Centered emoji/text inside a slim ring, plus a bottom caption.
* **Where used:**

    * **Slot 1**: Left emoji/text that opens proposed exercises (**MyComplicationProviderService**).
    * **Slot 4**: Restart/status text (**MyServiceAliveCheckComplicationProviderService**) inside a pressable circular region.

> The XML already specifies **default providers** so the face remains useful even without your custom services (e.g., steps, battery).

---

## Health-oriented UI principles baked into the XML

* **Single dominant metric:** A larger ring (Slot 0) makes the primary health goal unmistakable.
* **Big, readable time & date:** The clock sits on top of everything for constant visibility.
* **Ambient mode simplification:** Rings/text fade or move in ambient for clean low-power glanceability.
* **Color themes:** A light set of curated palettes supports contrast preferences and situational lighting.
* **Tap regions sized generously:** Interactive/centered areas (e.g., Restart) are easy to hit.

---

## File layout (suggested)

```
.
├── watchface.xml                # The Watch Face Format definition (this repo’s core)
├── README.md                    # This file
├── docs/
│   ├── screenshot_active.png
│   ├── screenshot_ambient.png
│   └── architecture.png         # (optional) show slot layout
└── providers/                   # (optional) your complication provider code (Kotlin/Java)
    ├── MyProgressComplicationProviderService.kt
    ├── MyWearTimeComplicationProviderService.kt
    └── MyServiceAliveCheckComplicationProviderService.kt
```

---

## How to plug in your data

> You will add these later—this section is just a placeholder for your future docs.

* **MyProgressComplicationProviderService** → emits a `RANGED_VALUE` (e.g., active minutes toward goal).
* **MyWearTimeComplicationProviderService** → emits a `RANGED_VALUE` derived from daily “watch-worn” minutes.
* **MyComplicationProviderService** → emits a `SHORT_TEXT` (emoji or brief label).
* **MyServiceAliveCheckComplicationProviderService** → emits status text (e.g., “RESTART”/“ALIVE”) as `SHORT_TEXT`.

You’ll document:

* How you compute `min`, `max`, and `value` for each `RANGED_VALUE`.
* Units and expected ranges.
* Any privacy or data-sharing considerations for health metrics.

---

## The .xml CODE
* **Face basics:** 450×450 **DIGITAL** watch face.
* **Themes:** User-selectable palettes via `themeColor`; reference colors as `[CONFIGURATION.themeColor.N]`.
* **Clock & date:** Shows date and time, with a thinner font in ambient.

**Complication slots**
* **Slot 0 — RANGED_VALUE (Main progress ring):** custom provider; circular bounds; sweep angle; value shown as `h'm` and label “active”; rendered only when data exists.
* **Slot 1 — SHORT_TEXT (Left emoji/label):** custom provider; circular bounds; red border, emoji/text, “exercises”; only shown in active mode.
* **Slot 2 — RANGED_VALUE (Wear time, top):** custom wear-time; ring + watch icon + `h'm` + “wear time”; only shown in active mode.
* **Slot 3 — RANGED_VALUE (Battery, right):** system WATCH_BATTERY; ring + icon + `%` + “battery”; active-only.
* **Slot 4 — SHORT_TEXT (Restart, bottom-center):** tap-enabled button; gray border + green fill + provider text.

**Useful to know**
* `<UserConfigurations>` with `ColorConfiguration` (theme options).
* `<ComplicationSlot>` blocks for Slots **0..4** with **supportedTypes** set to `RANGED_VALUE` or `SHORT_TEXT`.
* `<DefaultProviderPolicy>` pointing to your custom providers (and safe system fallbacks).
* `<Variant mode="AMBIENT">` sections to simplify visuals when the device is in ambient mode.
* `<DigitalClock>` and date **PartText** for time/date layers rendered last (on top).
* `when=[COMPLICATION.HAS_DATA]` gates drawing. The providers decide if the complication is shown or not.
* **BoundingOval** defines circular clip/tap regions (recommended for ring/round UI).
* **Transform endAngle** maps normalized value → 0–360°.
* `"%sh%s'"` template converts total minutes → hours:minutes.
* 
---

## License

MIT License

Copyright (c) 2025 Guillem Cornella-Barba

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.


---

## Citation / Acknowledgments
If you use this code or ideas, please cite this repo or my Google Scholar.

@software{CornellaBarba2025DiversifyWatchFace,
author  = {Cornella-Barba, Guillem},
title   = {Diversify Watch Face: A complication-driven Wear OS face for stroke rehabilitation},
year    = {2025},
url     = {https://github.com/<org>/<repo>},
scholar = {https://scholar.google.com/citations?hl=en&user=8V7UwdIAAAAJ},
version = {v1.0.0}
}

---

### Appendix: Slot Map

| Slot | Type         | Purpose          | Default Provider                                 |
| ---- | ------------ | ---------------- |--------------------------------------------------|
| 0    | RANGED_VALUE | Primary progress | `MyProgressComplicationProviderService`          |
| 1    | SHORT_TEXT   | Emoji/Label left | `MyComplicationProviderService`                  |
| 2    | RANGED_VALUE | Wear time        | `MyWearTimeComplicationProviderService`          |
| 3    | RANGED_VALUE | Battery          | System `WATCH_BATTERY`                           |
| 4    | SHORT_TEXT   | Restart/Status   | `MyServiceAliveCheckComplicationProviderService` |

---


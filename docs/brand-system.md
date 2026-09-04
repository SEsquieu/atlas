# Atlas brand system

Atlas should feel like the same persistent intelligence everywhere it appears.
The public site, Android reference app, documentation, and future managed
surfaces share one identity; they should not become independently themed
products.

## Core idea

Atlas is a signal that stays present while the world changes. It is not framed
as a robot, chatbot, dashboard, or single-purpose hardware assistant. Product
language should feel human and slightly uncanny, while operational language
should remain exact.

The primary invitation is **Stay with me.**

## Visual primitives

| Role | Value | Use |
| --- | --- | --- |
| Field black | `#010302` | Primary background |
| Surface black | `#060A07` | Controls and raised surfaces |
| Signal green | `#5CFF78` | Active state, action, focus, identity |
| Primary text | `#EEF5EF` | Headlines and essential content |
| Secondary text | `#A1ACA4` | Explanation and passive state |
| Active surface | `#0D2514` | Selected navigation and live context |

- The canonical lockup is **ATLAS //**.
- Headlines use a clean sans face with restrained negative tracking.
- Status, routing, timestamps, and system labels use monospace uppercase text.
- Geometry is square by default. Circles are reserved for signal dots, live
  capture, and genuinely continuous state.
- Green means live, selected, permitted, or available. It is not decorative
  filler.
- Motion should suggest persistence, scanning, or a thread continuing through
  time. Avoid generic card entrances and dashboard activity.

## Voice

Public language starts with the human experience: continuity, presence,
interruption, memory, and the freedom to bring Atlas into unfamiliar situations.
Architecture follows as the explanation for why that experience is possible.

Prefer:

- “One continuous thread.”
- “Bring whatever brain you want.”
- “The model thinks. Atlas lives.”
- “Managed Atlas cloud inference is on the way.”

Avoid reducing Atlas to repair, workshops, cameras, Android, or a fixed list of
use cases. Those are demonstrations, not the product boundary.

## Product truth

- Bring-your-own inference is available without an Atlas account.
- Managed Atlas cloud inference is **on the way** and must never be presented as
  generally available until its release gates are complete.
- Atlas Core owns the session. Inference providers remain replaceable.
- Safety, privacy, freshness, and policy states should be visible in plain
  language rather than hidden behind brand styling.

## Surface mapping

| Website | Android |
| --- | --- |
| ATLAS // masthead | ATLAS // screen header |
| Signal dot and operational kicker | Runtime phase and local status |
| Black editorial field | Near-black native application background |
| Electric-green active moments | Selected navigation, live context, primary action |
| Interrupted conversation narrative | Durable session conversation |
| Capability route tape | Inference configuration |

Native usability wins when a visual rule conflicts with accessibility or Android
interaction conventions. Familiarity should come from identity, hierarchy, and
language—not by making the app behave like a web page.

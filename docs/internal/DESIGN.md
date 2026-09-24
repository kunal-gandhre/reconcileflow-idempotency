<!--
  Copyright 2026 ReconcileFlow
  Author: Kunal Gandhre
  SPDX-License-Identifier: Apache-2.0
  Licensed under the Apache License, Version 2.0; see LICENSE.
  https://www.apache.org/licenses/LICENSE-2.0
-->

# Website design reference

The built-in image generation tool produced the initial website concept on 2026-09-24. The reference image is a design aid, not a shipped page asset; all UI, diagrams, text and controls are implemented in HTML/CSS/JavaScript.

## Design system

- True white page; ink `#090e18`; cobalt accent `#0755ff`; muted text `#546078`; rules `#dce3ef`; quickstart band `#f0f4f8`.
- Segoe UI / Inter / Arial sans-serif; monospace code. Large, tightly spaced two-line hero; medium section titles; simple small navigation.
- Open two-column hero, code-native event/Redis/result diagram, three numbered steps divided by fine rules, pale quickstart band and minimal footer.
- Blue primary button, outlined secondary button, underlined language tabs, dark code surface; six-pixel panel radii.
- Mobile stacks hero and quickstart, turns the steps into rows, keeps diagram labels compact, and allows code to scroll inside its own surface.

## Intentional changes from generated concept

Removed an unsolicited hero eyebrow and the inaccurate “exactly once in our code” phrase. Corrected the diagram to distinguish busy processing from completed duplicates. Used actual annotation and middleware APIs instead of invented Maven coordinates. Added a GitHub link, a labeled delivery simulation and a disclosure explaining known guarantees. These corrections preserve the composition while making the page technically accurate.

## Original image generation prompt

> Use case ui-mockup. Create a polished complete compact single page developer library website design for ReconcileFlow, a Java and Go Kafka idempotency library. Desktop screenshot 1440 wide. White background, nearly black typography, vivid cobalt blue accent, thin gray rules, large modern grotesk headings, restrained technical aesthetic. Full surface: simple header ReconcileFlow / How it works / Quickstart; split hero left headline 'Repeat delivery. Not the work.' supporting 'Redis-backed deduplication for Kafka consumers. Built for Spring Boot and Go.' buttons 'Get started' and 'Explore the flow'; right a beautiful code-native pipeline visual incoming event cards converging to Redis state gate and one processed event, duplicate routed below. Below hero a three column open numbered row '01 Claim / 02 Process / 03 Remember' with short descriptions. Bottom full width pale gray Quickstart section with Java / Go tabs and dark code block, adjacent text 'A small library. An explicit contract.' and note 'Deduplication within a retention window. Not exactly-once side effects.' Footer 'ReconcileFlow / Apache 2.0 / Early preview'. No badges, invented metrics, testimonials or pricing. All page controls and diagram will be HTML CSS, no asset imagery required. Complete readable page concept including bottom.

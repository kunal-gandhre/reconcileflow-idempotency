/*
 * Copyright 2026 ReconcileFlow
 * Author: Kunal Gandhre
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0; see LICENSE.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

import { mkdir, copyFile } from "node:fs/promises";
// Create an output directory without bundling or rewriting the authored assets.
await mkdir(new URL("./dist/", import.meta.url), { recursive: true });
// An explicit allowlist keeps development scripts and package metadata out of the published site.
for (const name of ["index.html", "style.css", "app.js", "favicon.svg"])
  await copyFile(
    new URL(name, import.meta.url),
    new URL("dist/" + name, import.meta.url),
  );
console.log("Static site built in website/dist");

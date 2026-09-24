/*
 * Copyright 2026 ReconcileFlow
 * Author: Kunal Gandhre
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0; see LICENSE.
 * https://www.apache.org/licenses/LICENSE-2.0
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";
// Resolve assets relative to this script so starting from another directory is safe.
const root = fileURLToPath(new URL(".", import.meta.url));
// Allow only public site asset types; repository metadata is not served.
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css",
  ".js": "text/javascript",
  ".svg": "image/svg+xml",
};
/** Minimal loopback development server; production hosts the built static files. */
createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    const file = resolve(
      root,
      "." + (pathname === "/" ? "/index.html" : pathname),
    );
    // Resolve and check containment before reading files, including encoded traversal attempts.
    if (
      !file.startsWith(root.endsWith(sep) ? root : root + sep) ||
      !types[extname(file)]
    ) {
      res.writeHead(404).end("Not found");
      return;
    }
    res
      .writeHead(200, {
        "Content-Type": types[extname(file)],
        "X-Content-Type-Options": "nosniff",
      })
      .end(await readFile(file));
  } catch {
    // Missing files and invalid URL encodings share a simple non-disclosing response.
    res.writeHead(404).end("Not found");
  }
}).listen(4173, "127.0.0.1", () =>
  console.log("ReconcileFlow: http://127.0.0.1:4173"),
);

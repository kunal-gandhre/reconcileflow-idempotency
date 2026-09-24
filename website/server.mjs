import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL(".", import.meta.url));
const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css",
  ".js": "text/javascript",
  ".svg": "image/svg+xml",
};
createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    const file = resolve(
      root,
      "." + (pathname === "/" ? "/index.html" : pathname),
    );
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
    res.writeHead(404).end("Not found");
  }
}).listen(4173, "127.0.0.1", () =>
  console.log("ReconcileFlow: http://127.0.0.1:4173"),
);

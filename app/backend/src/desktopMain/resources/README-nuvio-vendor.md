# Vendored JS dependencies

Real-world Nuvio scrapers frequently `require()` a small set of npm packages
that assume a JS environment (Node, or React Native via Metro) rather than
the restricted runtimes Cove provides. Roughly half of the scrapers in
real-world community plugin collections depend on one or both of these two
packages, so they are vendored here as pre-bundled, dependency-free CommonJS
files. `NuvioSandbox.kt` registers them for the desktop GraalJS child process;
`AndroidNuvioSandbox.kt` registers the same assets for the isolated QuickJS
service. `app/mobile` packages this resource directory as Android assets.

- `crypto-js.js` — vendored from `crypto-js@4.2.0`. Hash and cipher operations
  are self-contained. Its secure-random helper looks for Web Crypto and then
  Node's `crypto` module; Cove's sandboxes deliberately expose neither, so a
  scraper must not depend on `CryptoJS.lib.WordArray.random()` unless the
  sandbox gains an explicit randomness bridge.
- `cheerio-without-node-native.js` — vendored from
  `cheerio-without-node-native@0.20.2` (a cheerio build with its HTML-parsing
  dependency tree — `css-select`, `dom-serializer`, `entities`,
  `htmlparser2-without-node-native` — bundled in, built specifically for
  environments without Node's native modules, e.g. React Native/Hermes).
  Its `require("util")` fallback is similarly guarded by try/catch upstream
  and never actually needed.

## Regenerating

Both were produced with esbuild, bundling each package's own dependency tree
into one file with no external `require()`s left except the two Node
built-ins noted above. Those calls are guarded by the packages' own try/catch
fallbacks, but `CryptoJS.lib.WordArray.random()` still requires a host-provided
secure-random source as described above:

```sh
mkdir /tmp/vendor-bundle && cd /tmp/vendor-bundle
npm init -y
npm install crypto-js@4.2.0 cheerio-without-node-native@0.20.2
npx esbuild node_modules/crypto-js/index.js --bundle --platform=node --format=cjs --minify --outfile=crypto-js.js
npx esbuild node_modules/cheerio-without-node-native/index.js --bundle --platform=node --format=cjs --minify --outfile=cheerio-without-node-native.js
```

Then copy the two output files here, replacing the existing ones.

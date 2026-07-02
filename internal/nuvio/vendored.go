package nuvio

import (
	"embed"
	"fmt"

	"github.com/dop251/goja"
	"github.com/dop251/goja_nodejs/require"
)

// vendoredFS holds pre-bundled, dependency-free CommonJS builds of the two
// npm packages real-world Nuvio scrapers most commonly `require()` — see
// vendor/README.md for provenance and how to regenerate them.
//
//go:embed vendor/crypto-js.js vendor/cheerio-without-node-native.js
var vendoredFS embed.FS

// vendoredModules maps the exact require() specifier scraper code uses to
// the embedded bundle that satisfies it.
var vendoredModules = map[string]string{
	"crypto-js":                   "vendor/crypto-js.js",
	"cheerio-without-node-native": "vendor/cheerio-without-node-native.js",
}

// vendoredPrograms caches each bundle compiled once (goja.Program is
// reusable/immutable across goja.Runtime instances), so the relatively large
// cheerio bundle (~190KB minified) isn't re-parsed on every single scraper
// invocation — only compiled once per process.
var vendoredPrograms = map[string]*goja.Program{}

func init() {
	for name, path := range vendoredModules {
		src, err := vendoredFS.ReadFile(path)
		if err != nil {
			panic(fmt.Sprintf("nuvio: embedded vendored module %s missing: %v", path, err))
		}
		// Wrap in a CommonJS function so module/exports are real function
		// parameters, not globals — this runs in the same goja.Runtime as the
		// scraper's own top-level code, and using globals here would clobber
		// the scraper's own module/exports set up in runScraper.
		wrapped := "(function(module, exports) {" + string(src) + "\n})"
		prog, err := goja.Compile(name, wrapped, false)
		if err != nil {
			panic(fmt.Sprintf("nuvio: vendored module %s failed to compile: %v", name, err))
		}
		vendoredPrograms[name] = prog
	}
}

// registerVendoredModules makes the vendored packages requireable in a fresh
// per-invocation registry (see runScraper), so scrapers depending on them
// work instead of failing with "Invalid module".
func registerVendoredModules(registry *require.Registry) {
	for name, prog := range vendoredPrograms {
		prog := prog
		registry.RegisterNativeModule(name, func(vm *goja.Runtime, module *goja.Object) {
			fnVal, err := vm.RunProgram(prog)
			if err != nil {
				panic(err)
			}
			fn, ok := goja.AssertFunction(fnVal)
			if !ok {
				panic(vm.NewTypeError("vendored module did not compile to a function"))
			}
			exports := module.Get("exports")
			if _, err := fn(goja.Undefined(), module, exports); err != nil {
				panic(err)
			}
		})
	}
}

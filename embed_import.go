//go:build embedweb

// Blank import pulls the embedded web UI into desktop/server builds via web/embed.go.
package main

import _ "github.com/coveninja/cove/web"

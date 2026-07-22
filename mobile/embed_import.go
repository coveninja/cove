//go:build embedweb

// Blank import pulls the embedded web UI into gomobile builds via web/embed.go.
package mobile

import _ "github.com/coveninja/cove/web"

//go:build embedweb

package webembed

import (
	"embed"

	"github.com/coveninja/cove/internal/webstatic"
)

//go:embed all:dist
var dist embed.FS

func init() { webstatic.Register(dist) }

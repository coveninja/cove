// Package webstatic optionally serves the compiled Svelte frontend at "/".
// It is a no-op by default; call Register before Mount to activate it.
// The embedweb build tag (web/embed.go) calls Register via init().
package webstatic

import (
	"io/fs"
	"log"
	"net/http"
)

var registeredFS fs.FS

// Register stores fsys as the filesystem to serve. It is called by the
// embedweb init() function with the embedded web/dist tree.
func Register(fsys fs.FS) {
	registeredFS = fsys
}

// Mount attaches a file server for the registered FS to mux at "/".
// It is a no-op when no FS has been registered (desktop builds without
// the embedweb tag).
func Mount(mux *http.ServeMux) {
	if registeredFS == nil {
		return
	}
	sub, err := fs.Sub(registeredFS, "dist")
	if err != nil {
		log.Println("webstatic: sub dist:", err)
		return
	}
	mux.Handle("/", http.FileServer(http.FS(sub)))
}

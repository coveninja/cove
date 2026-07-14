package nuvio

import (
	"encoding/json"
	"os"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

type nuvioStore struct {
	Repos []Repo `json:"repos"`
	// UpdatedAt records when the store was last mutated locally. Used for
	// whole-store LWW resolution during cross-device sync.
	UpdatedAt time.Time `json:"updatedAt,omitempty"`
}

func loadStore(path string) (nuvioStore, error) {
	var s nuvioStore
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return s, err
	}
	err = json.Unmarshal(data, &s)
	return s, err
}

func saveStore(path string, s nuvioStore) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(path, data, 0o644)
}

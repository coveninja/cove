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
	if err := json.Unmarshal(data, &s); err != nil {
		return s, err
	}
	// Stores written before UpdatedAt existed carry a zero timestamp, which
	// would make their (real) repo config lose every LWW sync merge and push
	// a zero updated_at that no other device accepts. Fall back to the file's
	// mtime so pre-existing data participates in sync ordering sensibly.
	if s.UpdatedAt.IsZero() && len(s.Repos) > 0 {
		if fi, statErr := os.Stat(path); statErr == nil {
			s.UpdatedAt = fi.ModTime().UTC()
		}
	}
	return s, nil
}

func saveStore(path string, s nuvioStore) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(path, data, 0o644)
}

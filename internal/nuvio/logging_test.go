package nuvio

import (
	"bytes"
	"context"
	"log"
	"strings"
	"testing"
	"time"
)

// TestRunScraper_LogsOnlyErrors locks in the fix for real-world log noise:
// scrapers call console.log/logger.log liberally for their own debugging,
// which used to flood the app log with no operator value. Only
// console.warn/error and logger.error should reach the process log.
func TestRunScraper_LogsOnlyErrors(t *testing.T) {
	var buf bytes.Buffer
	orig := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(orig)

	code := `
		function getStreams() {
			console.log("noisy debug line");
			console.info("also noisy");
			console.debug("also noisy");
			logger.log("noisy logger line");
			console.warn("a real warning");
			console.error("a real error");
			logger.error("a real logger error");
			return [{ name: "n", title: "t", url: "http://example.com/x" }];
		}
		module.exports = { getStreams };
	`
	_, err := runScraper(context.Background(), "test-logging", code, 5*time.Second, 1, "movie", "Test", 2020, "tt0000001", nil, nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	output := buf.String()
	for _, noisy := range []string{"noisy debug line", "also noisy", "noisy logger line"} {
		if strings.Contains(output, noisy) {
			t.Errorf("expected %q to be silenced, but it appeared in the log:\n%s", noisy, output)
		}
	}
	for _, real := range []string{"a real warning", "a real error", "a real logger error"} {
		if !strings.Contains(output, real) {
			t.Errorf("expected %q to be logged, but it didn't appear:\n%s", real, output)
		}
	}
}

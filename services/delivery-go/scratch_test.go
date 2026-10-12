package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestScratchCleanupKeepsActiveAndUnrelatedFiles(t *testing.T) {
	root := filepath.Join(t.TempDir(), "private")
	if err := cleanScratch(root, time.Now()); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-25 * time.Hour)
	for _, name := range []string{"report-delivery-123.pdf", "report-delivery-456.pdf", "unrelated.pdf"} {
		path := filepath.Join(root, name)
		if err := os.WriteFile(path, []byte("synthetic"), 0600); err != nil {
			t.Fatal(err)
		}
		if name != "report-delivery-456.pdf" {
			if err := os.Chtimes(path, old, old); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := os.Symlink(filepath.Join(root, "unrelated.pdf"), filepath.Join(root, "report-delivery-789.pdf")); err != nil {
		t.Fatal(err)
	}
	if err := cleanScratch(root, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "report-delivery-123.pdf")); !os.IsNotExist(err) {
		t.Fatal("stale upload was retained")
	}
	for _, name := range []string{"report-delivery-456.pdf", "unrelated.pdf", "report-delivery-789.pdf"} {
		if _, err := os.Lstat(filepath.Join(root, name)); err != nil {
			t.Fatalf("removed protected file %s", name)
		}
	}
	if err := os.Chmod(root, 0755); err != nil {
		t.Fatal(err)
	}
	if cleanScratch(root, time.Now()) == nil {
		t.Fatal("accepted public scratch directory")
	}
}

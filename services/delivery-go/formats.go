package main

import (
	"path"
	"strings"
)

// The immutable generated object extension is the v1 wire format discriminator.
// Keeping the payload shape unchanged preserves fingerprints of accepted PDF tasks.
func mediaType(key string) string {
	switch path.Ext(key) {
	case ".pdf":
		return "application/pdf"
	case ".xlsx":
		return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	case ".docx":
		return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
	case ".zip":
		if strings.HasSuffix(key, ".html.zip") {
			return "application/zip"
		}
		return "application/octet-stream"
	case ".csv":
		return "text/csv; charset=UTF-8"
	default:
		return "application/octet-stream"
	}
}

// Keep the package discriminator in suggested filenames, including signed links.
func artifactExtension(key string) string {
	if strings.HasSuffix(key, ".html.zip") {
		return ".html.zip"
	}
	return path.Ext(key)
}

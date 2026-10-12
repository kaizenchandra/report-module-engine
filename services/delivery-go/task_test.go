package main

import "testing"

func TestTaskRejectsUntrustedDestinationsAndPaths(t *testing.T) {
	id := "b4928e1f-63af-41a2-8f96-bfd91c207071"
	valid := Task{Provider: "S3", Bucket: "report-results", ID: id, ArtifactID: id, DestinationRef: "local-s3", ObjectKey: "reports/" + id + ".pdf", SHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Size: 120}
	if err := valid.validate(); err != nil {
		t.Fatal(err)
	}
	cases := []Task{valid, valid, valid, valid}
	cases[0].ObjectKey = "../../credentials"
	cases[1].DestinationRef = "https://169.254.169.254"
	cases[2].Size = 11 * 1024 * 1024
	cases[3].SHA256 = "not-a-checksum"
	for _, test := range cases {
		if test.validate() == nil {
			t.Fatalf("accepted unsafe task: %+v", test)
		}
	}
}

func TestRegisteredAliasAndSafePrefixes(t *testing.T) {
	id := "b4928e1f-63af-41a2-8f96-bfd91c207071"
	task := Task{Provider: "GCS", Bucket: "report-results", ID: id, ArtifactID: id, DestinationRef: "finance-archive", ObjectKey: "finance/2026/" + id + ".pdf", SHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Size: 120}
	if err := task.validate(); err != nil {
		t.Fatal(err)
	}
	for _, prefix := range []string{"../", "/absolute/", "a//", "a/../", "a%2fb/", "https://host/", "a\\b/"} {
		task.ObjectKey = prefix + id + ".pdf"
		if task.validate() == nil {
			t.Fatalf("accepted prefix %q", prefix)
		}
	}
}

func TestDeliveryIdentityIsIndependentOfArtifact(t *testing.T) {
	artifact := "b4928e1f-63af-41a2-8f96-bfd91c207071"
	delivery := "7b339a7c-e73e-49fa-aa03-6f363d88e3f6"
	task := Task{Provider: "S3", Bucket: "report-results", ID: delivery, ArtifactID: artifact, DestinationRef: "local-s3", ObjectKey: "reports/" + artifact + ".pdf", SHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Size: 120}
	if err := task.validate(); err != nil {
		t.Fatal(err)
	}
	task.ObjectKey = "reports/" + delivery + ".pdf"
	if task.validate() == nil {
		t.Fatal("accepted key for the delivery rather than the artifact")
	}
	task.ArtifactID = "../../untrusted"
	if task.validate() == nil {
		t.Fatal("accepted malformed artifact identity")
	}
}

func TestDocumentAndHtmlPackageFormats(t *testing.T) {
	id := "b4928e1f-63af-41a2-8f96-bfd91c207071"
	task := Task{Provider: "S3", Bucket: "report-results", ID: id, ArtifactID: id, DestinationRef: "local-s3", SHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Size: 120}
	for suffix, media := range map[string]string{"docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "html.zip": "application/zip"} {
		task.ObjectKey = "reports/" + id + "." + suffix
		if err := task.validate(); err != nil {
			t.Fatal(err)
		}
		if mediaType(task.ObjectKey) != media || artifactExtension(task.ObjectKey) != "."+suffix {
			t.Fatal("incorrect artifact metadata")
		}
	}
	for _, suffix := range []string{"html", "zip", "html.zip.exe", "docm", "htmlXzip"} {
		task.ObjectKey = "reports/" + id + "." + suffix
		if task.validate() == nil {
			t.Fatalf("accepted unsupported suffix %s", suffix)
		}
	}
}

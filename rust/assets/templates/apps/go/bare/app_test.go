package app

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// A visibly fake key: the secret is lgr_whsec_ + its base64.
var key = []byte("template-offline-test-secret-01!")

func TestASignedRenderReturnsACard(t *testing.T) {
	handler, err := Handler("lgr_whsec_" + base64.StdEncoding.EncodeToString(key))
	if err != nil {
		t.Fatal(err)
	}
	id := "lgr_msg_0123456789abcdef0123456789abcdef"
	body := `{"type":"app.render","id":"` + id + `","install_id":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","subject":"lgr_sub_template","slot":"home.side","locale":"zh-Hans","context":[]}`
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	// Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(id + "." + timestamp + "." + body))
	request := httptest.NewRequest("POST", "/", strings.NewReader(body))
	request.Header.Set("content-type", "application/json")
	request.Header.Set("webhook-id", id)
	request.Header.Set("webhook-timestamp", timestamp)
	request.Header.Set("webhook-signature", "v1,"+base64.StdEncoding.EncodeToString(mac.Sum(nil)))
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != 200 {
		t.Fatalf("status %d, want 200: %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), `"card"`) {
		t.Fatalf("the reply carries no card: %s", recorder.Body.String())
	}
}

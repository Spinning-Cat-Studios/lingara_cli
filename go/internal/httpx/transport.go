package httpx

import (
	"bytes"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// WireRequest is one request on the wire, already spelled: method,
// absolute URL, headers and an optional body.
type WireRequest struct {
	Method  string
	URL     string
	Headers []Pair
	Body    []byte
}

// WireResponse is the raw answer.
type WireResponse struct {
	Status  int
	Headers []Pair
	Body    []byte
}

// Header is the first header of that name, case-insensitively.
func (r WireResponse) Header(name string) (string, bool) {
	for _, h := range r.Headers {
		if strings.EqualFold(h[0], name) {
			return h[1], true
		}
	}
	return "", false
}

// Transport is the one seam every request crosses: net/http in production,
// a scripted fake in the unit tests. An error is a connection failure:
// contract B2 rule 7.
type Transport interface {
	Send(req WireRequest) (WireResponse, error)
}

// NetHTTP is net/http with no redirects (an unexpected 3xx is B2 rule 8's
// to classify), a 10 s connect timeout and a 60 s overall one.
type NetHTTP struct {
	client *http.Client
}

// NewNetHTTP builds the production transport.
func NewNetHTTP() *NetHTTP {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	return &NetHTTP{client: &http.Client{
		Transport: transport,
		Timeout:   60 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}}
}

// Send sends one request and reads the whole answer.
func (n *NetHTTP) Send(req WireRequest) (WireResponse, error) {
	var body io.Reader
	if len(req.Body) > 0 {
		body = bytes.NewReader(req.Body)
	}
	r, err := http.NewRequest(req.Method, req.URL, body)
	if err != nil {
		return WireResponse{}, err
	}
	for _, h := range req.Headers {
		r.Header.Set(h[0], h[1])
	}
	resp, err := n.client.Do(r)
	if err != nil {
		return WireResponse{}, err
	}
	defer func() { _ = resp.Body.Close() }()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return WireResponse{}, err
	}
	out := WireResponse{Status: resp.StatusCode, Body: data}
	for k, values := range resp.Header {
		for _, v := range values {
			out.Headers = append(out.Headers, Pair{strings.ToLower(k), v})
		}
	}
	return out, nil
}

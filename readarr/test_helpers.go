package readarr

import "net/http"

// NewTestClient builds a Client suitable for unit tests without background refresh.
func NewTestClient(baseURL string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = &http.Client{}
	}
	return &Client{
		baseURL:    baseURL,
		httpClient: httpClient,
	}
}

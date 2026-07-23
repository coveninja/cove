package utils

import (
	"context"
	"net/netip"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestValidatePublicURL(t *testing.T) {
	for _, raw := range []string{
		"https://example.com/video.m3u8",
		"http://example.com:8080/path?token=one",
	} {
		t.Run("accept_"+raw, func(t *testing.T) {
			u, err := ValidatePublicURL(raw)
			require.NoError(t, err)
			assert.NotEmpty(t, u.Host)
		})
	}

	for _, raw := range []string{
		"", "example.com/path", "file:///etc/passwd", "ftp://example.com/file",
		"javascript:alert(1)", "https:///missing-host",
	} {
		t.Run("reject_"+raw, func(t *testing.T) {
			_, err := ValidatePublicURL(raw)
			assert.Error(t, err)
		})
	}
}

func TestIsPublicAddr(t *testing.T) {
	tests := []struct {
		address string
		public  bool
	}{
		{"8.8.8.8", true},
		{"2606:4700:4700::1111", true},
		{"::ffff:8.8.8.8", true},
		{"127.0.0.1", false},
		{"::1", false},
		{"10.0.0.1", false},
		{"172.16.0.1", false},
		{"192.168.1.1", false},
		{"169.254.1.1", false},
		{"fe80::1", false},
		{"224.0.0.1", false},
		{"ff02::1", false},
		{"0.0.0.0", false},
		{"::", false},
	}
	for _, test := range tests {
		t.Run(test.address, func(t *testing.T) {
			assert.Equal(t, test.public, isPublicAddr(netip.MustParseAddr(test.address)))
		})
	}
}

func TestSafeTransportRefusesLiteralPrivateAddresses(t *testing.T) {
	transport := SafeTransport()
	defer transport.CloseIdleConnections()

	for _, address := range []string{"127.0.0.1:80", "10.0.0.1:443", "[::1]:80"} {
		t.Run(address, func(t *testing.T) {
			conn, err := transport.DialContext(context.Background(), "tcp", address)
			if conn != nil {
				conn.Close()
			}
			require.Error(t, err)
			assert.Contains(t, err.Error(), "refusing to connect to non-public address")
		})
	}
}

func TestSafeTransportRejectsMalformedAddress(t *testing.T) {
	transport := SafeTransport()
	_, err := transport.DialContext(context.Background(), "tcp", "missing-port")
	assert.Error(t, err)
}

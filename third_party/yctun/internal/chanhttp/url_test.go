package chanhttp

import (
	"net/url"
	"testing"
)

func TestJoinURLPath(t *testing.T) {
	tests := []struct {
		base string
		p    string
		want string
	}{
		{
			base: "https://functions.yandexcloud.net/abc",
			p:    "/s/x",
			want: "https://functions.yandexcloud.net/abc/s/x",
		},
		{
			base: "https://dom.sa05.eu.cc",
			p:    "/s/x/hello",
			want: "https://dom.sa05.eu.cc/s/x/hello",
		},
		{
			base: "https://dom.sa05.eu.cc/",
			p:    "/s/x",
			want: "https://dom.sa05.eu.cc/s/x",
		},
	}
	for _, tt := range tests {
		u, err := url.Parse(tt.base)
		if err != nil {
			t.Fatalf("parse %q: %v", tt.base, err)
		}
		if got := JoinURLPath(u, tt.p); got != tt.want {
			t.Errorf("joinURLPath(%q, %q) = %q, want %q", tt.base, tt.p, got, tt.want)
		}
	}
}

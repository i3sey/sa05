package chanhttp

import (
	"net/url"
	"strings"
)

// JoinURLPath склеивает path базового URL с относительным путём запроса.
func JoinURLPath(base *url.URL, p string) string {
	u := *base
	u.Path = joinPath(base.Path, p)
	u.RawQuery = ""
	return u.String()
}

// functionEntry — вход через functions.yandexcloud.net (без subpath в URL).
func functionEntry(base *url.URL) bool {
	return base.Host == "functions.yandexcloud.net"
}

// TunnelRequestURL возвращает URL запроса и опциональный путь для X-Yctun-Path.
func TunnelRequestURL(base *url.URL, p string) (reqURL, pathHeader string) {
	return tunnelRequestURL(base, p)
}

// tunnelRequestURL возвращает URL запроса и опциональный путь для X-Yctun-Path.
func tunnelRequestURL(base *url.URL, p string) (reqURL, pathHeader string) {
	if functionEntry(base) {
		u := *base
		if u.Path == "" {
			u.Path = "/"
		}
		return u.String(), p
	}
	return JoinURLPath(base, p), ""
}

func joinPath(basePath, p string) string {
	if p == "" {
		return basePath
	}
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	if basePath == "" || basePath == "/" {
		return p
	}
	return strings.TrimSuffix(basePath, "/") + p
}

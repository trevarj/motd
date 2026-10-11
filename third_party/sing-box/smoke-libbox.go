// Run from the pinned sing-box checkout with the feature tags in source.lock.
// This exercises real libbox constructors and the command server's shared service
// lifecycle on the host; it cannot validate Android JNI/loading or remote TLS.
package main

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"strings"
	"time"

	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/include"
)

func main() {
	if libbox.Version() != "1.13.12" {
		panic("libbox.Version must preserve upstream's unprefixed release version")
	}
	note := libbox.DeprecatedNote{ScheduledVersion: "1.14.0"}
	if !note.Impending() {
		panic("release version must retain semver-dependent deprecation warnings")
	}
	port, err := libbox.AvailablePort(20000)
	must(err)
	privateKey, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{1}, 32))
	must(err)
	publicKey := base64.RawURLEncoding.EncodeToString(privateKey.PublicKey().Bytes())
	core := daemon.NewStartedService(daemon.ServiceOptions{
		Context: include.Context(context.Background()), LogMaxLines: 16,
	})
	defer core.Close()
	for _, outbound := range []string{
		fmt.Sprintf(`"tls":{"enabled":true,"server_name":"example.com","insecure":false,"utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"%s","short_id":"abcd"}}`, publicKey),
		`"tls":{"enabled":true,"server_name":"example.com","insecure":false},"transport":{"type":"ws","path":"/irc-vless","headers":{"Host":"example.com"}}`,
	} {
		config := fmt.Sprintf(`{"inbounds":[{"type":"socks","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"vless","tag":"motd-vless","server":"127.0.0.1","server_port":443,"uuid":"123e4567-e89b-12d3-a456-426614174000",%s}],"route":{"final":"motd-vless"}}`, port, outbound)
		must(libbox.CheckConfig(config))
		must(core.StartOrReloadService(config, &daemon.OverrideOptions{}))
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
		must(err)
		must(conn.SetDeadline(time.Now().Add(time.Second)))
		_, err = conn.Write([]byte{5, 1, 0})
		must(err)
		response := make([]byte, 2)
		_, err = io.ReadFull(conn, response)
		must(err)
		must(conn.Close())
		if !bytes.Equal(response, []byte{5, 0}) {
			panic("SOCKS5 no-auth negotiation failed")
		}
		if strings.Contains(outbound, `"reality"`) {
			if libbox.CheckConfig(strings.Replace(config, `"fingerprint":"chrome"`, `"fingerprint":"not-a-fingerprint"`, 1)) == nil {
				panic("invalid uTLS fingerprint was accepted")
			}
			if libbox.CheckConfig(strings.Replace(config, `"public_key":"`+publicKey+`"`, `"public_key":"invalid"`, 1)) == nil {
				panic("invalid REALITY public key was accepted")
			}
		}
	}
	must(core.CloseService())
	fmt.Println("libbox release version/deprecation API, REALITY/uTLS and WS/TLS configs, service reload/close, and SOCKS5 negotiation passed")
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

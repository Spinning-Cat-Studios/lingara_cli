// Command app serves {{display_name}} on 127.0.0.1.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	app "{{name}}"
)

func main() {
	secret := os.Getenv("LINGARA_APP_SECRET")
	if secret == "" {
		log.Fatal("LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`")
	}
	handler, err := app.Handler(strings.Split(secret, ",")...)
	if err != nil {
		log.Fatal(err) // a malformed secret fails here, at startup
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "{{port}}"
	}
	fmt.Printf("{{name}} is listening on http://127.0.0.1:%s/\n", port)
	log.Fatal(http.ListenAndServe("127.0.0.1:"+port, handler))
}

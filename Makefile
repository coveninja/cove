build-go:
	go build -o cove .

generate:
	tygo generate

dev: build-go generate
	cd electron && npm run dev
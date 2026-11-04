# Vectra PocketBase backend — local dev tasks.
#
#   make build     compile the server binary (pb/)
#   make run       build then serve on 127.0.0.1:8090
#   make test      go vet + go test for the whole module
#   make migrate   apply pending Go migrations to pb_data/data.db
#   make docker-build  build the deploy image (context is this folder, as on Coolify)
#
# Override variables on the command line, e.g. `make run ADDR=0.0.0.0:8090`.

GO   ?= go
BIN  ?= pb
ADDR ?= 127.0.0.1:8090

.PHONY: build run test vet migrate docker-build clean

build:
	$(GO) build -o $(BIN) .

run: build
	./$(BIN) serve --http=$(ADDR)

test: vet
	$(GO) test ./...

vet:
	$(GO) vet ./...

migrate: build
	./$(BIN) migrate up

# Build the deploy image. Coolify builds backend/Dockerfile with backend/ as the
# build context, the same as here.
docker-build:
	docker build -f Dockerfile -t vectra-pb:local .

clean:
	rm -f $(BIN)

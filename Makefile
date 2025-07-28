# Makefile for gcp-secrets library

# Get the current git commit hash for use in deps.edn
gethash:
	@echo "Current git commit hash:"
	@git rev-parse HEAD
	@echo ""
	@echo "Use this in your deps.edn like:"
	@echo 'genek/gcp-secrets {:git/url "https://github.com/genek/gcp-secrets.git"'
	@echo '                    :git/sha "'$$(git rev-parse HEAD)'"}'

# Short version of the hash (first 7 characters)
get-short-hash:
	@echo "Short git commit hash:"
	@git rev-parse --short HEAD

# Get the hash and copy to clipboard (macOS)
copy-hash:
	@git rev-parse HEAD | pbcopy
	@echo "Git hash copied to clipboard:"
	@git rev-parse HEAD

.PHONY: gethash get-short-hash copy-hash
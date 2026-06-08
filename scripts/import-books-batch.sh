#!/usr/bin/env bash
set -u

BASE_URL="http://localhost:8080"
BOOK_DIR=""
PATTERN="*.epub"
STATUS="完结"
AUTHOR=""
INTRO=""
COVER_URL=""
REBUILD_RAG="false"
DRY_RUN="false"

usage() {
  cat <<'EOF'
Usage:
  scripts/import-books-batch.sh --dir <book_dir> [options]

Options:
  --dir <book_dir>        Directory containing book files. Required.
  --base-url <url>        Backend base URL. Default: http://localhost:8080
  --pattern <glob>        File glob pattern. Default: *.epub
  --status <status>       Book status. Default: 完结
  --author <author>       Optional author applied to every book.
  --intro <intro>         Optional intro applied to every book.
  --cover-url <url>       Optional cover URL applied to every book.
  --rebuild-rag           Rebuild RAG chunks after each successful import.
  --dry-run               Print files that would be imported without uploading.
  -h, --help              Show this help.

Environment:
  ADMIN_TOKEN             Optional bearer token for protected admin endpoints.

Examples:
  scripts/import-books-batch.sh --dir /mnt/c/Users/hamster/books
  scripts/import-books-batch.sh --dir ./books --rebuild-rag
  ADMIN_TOKEN=xxx scripts/import-books-batch.sh --dir ./books --base-url http://localhost:8080
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir)
      BOOK_DIR="${2:-}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --pattern)
      PATTERN="${2:-}"
      shift 2
      ;;
    --status)
      STATUS="${2:-}"
      shift 2
      ;;
    --author)
      AUTHOR="${2:-}"
      shift 2
      ;;
    --intro)
      INTRO="${2:-}"
      shift 2
      ;;
    --cover-url)
      COVER_URL="${2:-}"
      shift 2
      ;;
    --rebuild-rag)
      REBUILD_RAG="true"
      shift
      ;;
    --dry-run)
      DRY_RUN="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$BOOK_DIR" ]]; then
  echo "Missing required option: --dir" >&2
  usage
  exit 1
fi

if [[ ! -d "$BOOK_DIR" ]]; then
  echo "Book directory does not exist: $BOOK_DIR" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required but not installed." >&2
  exit 1
fi

extract_book_id() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -r '.id // .data.id // empty'
    return
  fi
  echo "$json" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1
}

curl_auth_args=()
if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  curl_auth_args=(-H "Authorization: Bearer ${ADMIN_TOKEN}")
fi

shopt -s nullglob
files=("$BOOK_DIR"/$PATTERN)
shopt -u nullglob

if [[ ${#files[@]} -eq 0 ]]; then
  echo "No files matched: $BOOK_DIR/$PATTERN"
  exit 0
fi

echo "Base URL: $BASE_URL"
echo "Book dir: $BOOK_DIR"
echo "Pattern: $PATTERN"
echo "Matched files: ${#files[@]}"
echo

success_count=0
failure_count=0

for file in "${files[@]}"; do
  filename="$(basename "$file")"
  title="${filename%.*}"

  echo "==> Importing: $filename"
  echo "    title: $title"

  if [[ "$DRY_RUN" == "true" ]]; then
    continue
  fi

  form_args=(
    -F "file=@${file}"
    -F "title=${title}"
    -F "status=${STATUS}"
  )
  if [[ -n "$AUTHOR" ]]; then
    form_args+=(-F "author=${AUTHOR}")
  fi
  if [[ -n "$INTRO" ]]; then
    form_args+=(-F "intro=${INTRO}")
  fi
  if [[ -n "$COVER_URL" ]]; then
    form_args+=(-F "coverUrl=${COVER_URL}")
  fi

  response="$(curl -sS -X POST "${BASE_URL}/api/admin/books/import" "${curl_auth_args[@]}" "${form_args[@]}")"
  curl_code=$?
  if [[ $curl_code -ne 0 ]]; then
    echo "    FAILED: curl exited with code $curl_code"
    failure_count=$((failure_count + 1))
    echo
    continue
  fi

  book_id="$(extract_book_id "$response")"
  if [[ -z "$book_id" ]]; then
    echo "    FAILED: could not parse book id"
    echo "    response: $response"
    failure_count=$((failure_count + 1))
    echo
    continue
  fi

  echo "    imported bookId: $book_id"
  success_count=$((success_count + 1))

  if [[ "$REBUILD_RAG" == "true" ]]; then
    echo "    rebuilding RAG chunks..."
    rebuild_response="$(curl -sS -X POST "${BASE_URL}/api/admin/chunks/rebuild/${book_id}" "${curl_auth_args[@]}")"
    rebuild_code=$?
    if [[ $rebuild_code -ne 0 ]]; then
      echo "    RAG rebuild failed: curl exited with code $rebuild_code"
    else
      echo "    RAG rebuild response: $rebuild_response"
    fi
  fi

  echo
done

echo "Done."
echo "Imported: $success_count"
echo "Failed: $failure_count"

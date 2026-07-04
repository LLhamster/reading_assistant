#!/usr/bin/env bash
set -u

BASE_URL="http://localhost:8080"
BOOK_DIR=""
PATTERN="*.epub"
STATUS="完结"
STATUS_EXPLICIT="false"
AUTHOR=""
AUTHOR_EXPLICIT="false"
INTRO=""
COVER_URL=""
REBUILD_RAG="false"
DRY_RUN="false"
FILENAME_METADATA="false"
RESUME="true"
STATE_FILE=""

usage() {
  cat <<'EOF'
Usage:
  scripts/import-books-batch.sh --dir <book_dir> [options]

Options:
  --dir <book_dir>          Directory containing book files. Required.
  --base-url <url>          Backend base URL. Default: http://localhost:8080
  --pattern <glob>          File glob pattern. Default: *.epub
  --filename-metadata       Parse 书名__作者__状态.epub filenames.
  --status <status>         Override status for every book. Default: 完结
  --author <author>         Override author for every book.
  --intro <intro>           Optional intro applied to every book.
  --cover-url <url>         Optional cover URL applied to every book.
  --state-file <path>       Import state file. Default: <book_dir>/.http-reading-import-state.tsv
  --no-resume               Ignore unchanged-file entries from the local state.
  --rebuild-rag             Rebuild RAG chunks after each newly imported book.
  --dry-run                 Validate and print files without hashing or uploading.
  -h, --help                Show this help.

Filename metadata:
  乡土中国__费孝通__完结.epub
  某书__张三__连载中.epub
  Supported status values: 完结, 连载, 连载中

Environment:
  ADMIN_TOKEN               Optional bearer token for protected admin endpoints.

Examples:
  scripts/import-books-batch.sh --dir /home/hamster/books --filename-metadata
  scripts/import-books-batch.sh --dir ./books --filename-metadata --rebuild-rag
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
    --filename-metadata)
      FILENAME_METADATA="true"
      shift
      ;;
    --status)
      STATUS="${2:-}"
      STATUS_EXPLICIT="true"
      shift 2
      ;;
    --author)
      AUTHOR="${2:-}"
      AUTHOR_EXPLICIT="true"
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
    --state-file)
      STATE_FILE="${2:-}"
      shift 2
      ;;
    --no-resume)
      RESUME="false"
      shift
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

if [[ "$DRY_RUN" != "true" ]] && ! command -v sha256sum >/dev/null 2>&1; then
  echo "sha256sum is required but not installed." >&2
  exit 1
fi

if [[ -z "$STATE_FILE" ]]; then
  STATE_FILE="$BOOK_DIR/.http-reading-import-state.tsv"
fi

extract_book_id() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -r '.id // .data.id // empty'
    return
  fi
  echo "$json" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1
}

extract_import_disposition() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -r '.importDisposition // "IMPORTED"'
    return
  fi
  local disposition
  disposition="$(echo "$json" | sed -n 's/.*"importDisposition"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
  echo "${disposition:-IMPORTED}"
}

lookup_server_book_id() {
  local hash="$1"
  local json="$2"
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -r --arg hash "$hash" '.hashes[$hash] // empty'
    return
  fi
  echo "$json" | tr -d '\n' |
    sed -n "s/.*\"${hash}\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" |
    head -n 1
}

parse_filename_metadata() {
  local stem="$1"
  if [[ "$stem" != *"__"* ]]; then
    return 1
  fi
  local rest
  PARSED_TITLE="${stem%%__*}"
  rest="${stem#*__}"
  if [[ "$rest" == "$stem" || "$rest" != *"__"* ]]; then
    return 1
  fi
  PARSED_AUTHOR="${rest%%__*}"
  PARSED_STATUS="${rest#*__}"
  if [[ -z "$PARSED_TITLE" || -z "$PARSED_AUTHOR" || -z "$PARSED_STATUS"
        || "$PARSED_STATUS" == *"__"* ]]; then
    return 1
  fi
  case "$PARSED_STATUS" in
    完结)
      ;;
    连载|连载中)
      PARSED_STATUS="连载中"
      ;;
    *)
      return 2
      ;;
  esac
  return 0
}

curl_auth_args=()
if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  curl_auth_args=(-H "Authorization: Bearer ${ADMIN_TOKEN}")
fi

declare -A state_size=()
declare -A state_mtime=()
declare -A state_hash=()
declare -A state_book_id=()

load_state() {
  [[ "$RESUME" == "true" && -f "$STATE_FILE" ]] || return
  while IFS=$'\t' read -r filename size mtime hash book_id; do
    [[ -n "$filename" && "$filename" != "filename" ]] || continue
    state_size["$filename"]="$size"
    state_mtime["$filename"]="$mtime"
    state_hash["$filename"]="$hash"
    state_book_id["$filename"]="$book_id"
  done < "$STATE_FILE"
}

save_state() {
  local state_dir temp
  state_dir="$(dirname "$STATE_FILE")"
  mkdir -p "$state_dir"
  temp="${STATE_FILE}.tmp.$$"
  {
    printf 'filename\tsize\tmtime\tsha256\tbookId\n'
    for filename in "${!state_size[@]}"; do
      printf '%s\t%s\t%s\t%s\t%s\n' \
        "$filename" \
        "${state_size[$filename]}" \
        "${state_mtime[$filename]}" \
        "${state_hash[$filename]}" \
        "${state_book_id[$filename]}"
    done | sort
  } > "$temp"
  mv "$temp" "$STATE_FILE"
}

record_state() {
  local filename="$1" size="$2" mtime="$3" hash="$4" book_id="$5"
  state_size["$filename"]="$size"
  state_mtime["$filename"]="$mtime"
  state_hash["$filename"]="$hash"
  state_book_id["$filename"]="$book_id"
  save_state
}

load_state

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
echo "State file: $STATE_FILE"
echo

server_index='{"hashes":{}}'
if [[ "$DRY_RUN" != "true" ]]; then
  echo "Refreshing server-side import hash index..."
  if ! server_index="$(curl -fsS -X POST \
      "${BASE_URL}/api/admin/books/import-index/refresh" "${curl_auth_args[@]}")"; then
    echo "FAILED: could not refresh the server import index." >&2
    exit 1
  fi
  echo
fi

imported_count=0
duplicate_count=0
unchanged_count=0
invalid_count=0
failure_count=0

for file in "${files[@]}"; do
  filename="$(basename "$file")"
  stem="${filename%.*}"
  title="$stem"
  author="$AUTHOR"
  status="$STATUS"

  if [[ "$FILENAME_METADATA" == "true" ]]; then
    PARSED_TITLE=""
    PARSED_AUTHOR=""
    PARSED_STATUS=""
    parse_filename_metadata "$stem"
    parse_code=$?
    if [[ $parse_code -ne 0 ]]; then
      if [[ $parse_code -eq 2 ]]; then
        echo "==> INVALID: $filename"
        echo "    unsupported status; use 完结, 连载, or 连载中"
      else
        echo "==> INVALID: $filename"
        echo "    expected: 书名__作者__状态.epub"
      fi
      invalid_count=$((invalid_count + 1))
      echo
      continue
    fi
    title="$PARSED_TITLE"
    if [[ "$AUTHOR_EXPLICIT" != "true" ]]; then
      author="$PARSED_AUTHOR"
    fi
    if [[ "$STATUS_EXPLICIT" != "true" ]]; then
      status="$PARSED_STATUS"
    fi
  fi

  size="$(stat -c '%s' "$file")"
  mtime="$(stat -c '%Y' "$file")"
  if [[ "$RESUME" == "true"
        && "${state_size[$filename]:-}" == "$size"
        && "${state_mtime[$filename]:-}" == "$mtime"
        && -n "${state_book_id[$filename]:-}"
        && -n "${state_hash[$filename]:-}" ]]; then
    indexed_book_id="$(lookup_server_book_id "${state_hash[$filename]}" "$server_index")"
    if [[ "$indexed_book_id" == "${state_book_id[$filename]}" ]]; then
      echo "==> UNCHANGED: $filename (bookId=${state_book_id[$filename]})"
      unchanged_count=$((unchanged_count + 1))
      continue
    fi
    echo "==> STALE STATE: $filename (deleted or replaced bookId=${state_book_id[$filename]})"
  fi

  echo "==> Processing: $filename"
  echo "    title: $title"
  echo "    author: ${author:-未知作者}"
  echo "    status: $status"

  if [[ "$DRY_RUN" == "true" ]]; then
    echo
    continue
  fi

  hash="$(sha256sum "$file" | awk '{print $1}')"
  existing_book_id="$(lookup_server_book_id "$hash" "$server_index")"
  if [[ -n "$existing_book_id" ]]; then
    echo "    DUPLICATE: existing bookId=$existing_book_id"
    record_state "$filename" "$size" "$mtime" "$hash" "$existing_book_id"
    duplicate_count=$((duplicate_count + 1))
    echo
    continue
  fi

  form_args=(
    -F "file=@${file}"
    -F "title=${title}"
    -F "status=${status}"
  )
  if [[ -n "$author" ]]; then
    form_args+=(-F "author=${author}")
  fi
  if [[ -n "$INTRO" ]]; then
    form_args+=(-F "intro=${INTRO}")
  fi
  if [[ -n "$COVER_URL" ]]; then
    form_args+=(-F "coverUrl=${COVER_URL}")
  fi

  response="$(curl -sS -X POST "${BASE_URL}/api/admin/books/import" \
    "${curl_auth_args[@]}" "${form_args[@]}")"
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

  disposition="$(extract_import_disposition "$response")"
  record_state "$filename" "$size" "$mtime" "$hash" "$book_id"
  if [[ "$disposition" == "DUPLICATE" ]]; then
    echo "    DUPLICATE: existing bookId=$book_id"
    duplicate_count=$((duplicate_count + 1))
  else
    echo "    imported bookId: $book_id"
    imported_count=$((imported_count + 1))
  fi

  if [[ "$REBUILD_RAG" == "true" && "$disposition" != "DUPLICATE" ]]; then
    echo "    rebuilding RAG chunks..."
    if rebuild_response="$(curl -sS -X POST \
        "${BASE_URL}/api/admin/chunks/rebuild/${book_id}" "${curl_auth_args[@]}")"; then
      echo "    RAG rebuild response: $rebuild_response"
    else
      echo "    RAG rebuild failed"
    fi
  fi

  echo
done

echo "Done."
echo "Imported: $imported_count"
echo "Duplicate/skipped by hash: $duplicate_count"
echo "Unchanged/skipped by state: $unchanged_count"
echo "Invalid filenames: $invalid_count"
echo "Failed: $failure_count"
